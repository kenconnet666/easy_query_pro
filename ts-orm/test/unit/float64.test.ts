import assert from "node:assert/strict";
import test from "node:test";
import {
  AggregateExpression,
  aggregate,
  column,
  createDatabase,
  dialect,
  frame,
  nativeCast,
  postgresDialect,
  table,
  types,
  valueOf,
  windowSpec,
} from "../../src/index.js";

test("dispersion operators share the approximate domain and reject DISTINCT at both boundaries", () => {
  const input = valueOf("0.0001", types.decimal(19, 4));
  const source = table("samples", { id: column(types.integer()) });
  const variants = [
    [aggregate.variancePopulationApprox, "VAR_POP"],
    [aggregate.varianceSampleApprox, "VAR_SAMP"],
    [aggregate.standardDeviationPopulationApprox, "STDDEV_POP"],
    [aggregate.standardDeviationSampleApprox, "STDDEV_SAMP"],
  ] as const;
  for (const [factory, operator] of variants) {
    const expression = factory(input).filter(valueOf(true, types.boolean()));
    assert.throws(() => {
      // @ts-expect-error Filtering must retain the statistical DISTINCT restriction.
      expression.distinct();
    }, /do not support DISTINCT/);
    const compiled = createDatabase({ dialect: postgresDialect })
      .from({ s: source })
      .select(() => ({ value: expression }))
      .compile();
    assert.ok(compiled.sql.includes(operator + "(CASE WHEN"));
    assert.match(
      compiled.sql,
      /THEN CAST\(CAST\(\$2 AS DECIMAL\(19,4\)\) AS DOUBLE PRECISION\) ELSE NULL END/,
    );
    assert.deepEqual(compiled.parameters, [true, "0.0001"]);
    assert.equal(compiled.decode({ _eq_0: null }).value, null);
  }
  const raw = new AggregateExpression(
    {
      kind: "aggregate",
      operator: "VAR_POP",
      operand: input.node,
      operandType: input.type,
      distinct: true,
    },
    types.float64().nullable(),
  );
  assert.throws(
    () =>
      createDatabase({ dialect: postgresDialect })
        .from({ s: source })
        .select(() => ({ raw }))
        .compile(),
    /do not support DISTINCT/,
  );
});

test("FLOAT64 accepts finite numbers and decimal/scientific driver text without implicit coercion", () => {
  const float = types.float64();
  for (const value of [0, 0.125, -0.5, 1e100, 1e-100, Number.MAX_VALUE, Number.MIN_VALUE]) {
    assert.equal(float.encode(value), value);
    assert.equal(float.decode(String(value)), value);
  }
  assert.equal(Object.is(float.encode(-0), 0), true);
  assert.equal(Object.is(float.decode("-0.0e99"), 0), true);
  for (const value of [
    NaN,
    Infinity,
    -Infinity,
    "NaN",
    "Infinity",
    "",
    " ",
    "0xff",
    "1_000",
    "1px",
    "1e400",
    false,
    1n,
    {},
  ])
    assert.throws(() => float.decode(value), TypeError);
  assert.throws(() => float.decode("1e-400"), RangeError);
  assert.throws(() => {
    // @ts-expect-error Parameters do not implicitly parse strings.
    float.encode("1.5");
  }, /number values/);
  assert.equal(float.nullable().decode(null), null);
  assert.throws(() => float.decode(null), /SQL NULL/);
});

for (const name of ["postgres", "mysql", "hana"] as const)
  test(`${name} approximate AVG selects its numeric domain before aggregation`, () => {
    const samples = table("samples", {
      id: column(types.integer()),
      value: column(types.decimal(19, 4)).nullable(),
      observed: column(types.float64()).nullable(),
    });
    const db = createDatabase({ dialect: dialect(name) });
    const result = db
      .from({ s: samples })
      .select(({ s }) => ({
        mean: aggregate.avgApprox(s.value).distinct().filter(s.id.gt(0)),
        native: aggregate.avgApprox(s.observed),
        integer: aggregate.avgApprox(s.id),
      }))
      .compile();
    assert.match(
      result.sql,
      /AVG\(DISTINCT CASE WHEN .* THEN CAST\(.* AS DOUBLE(?: PRECISION)?\) ELSE NULL END\)/,
    );
    assert.deepEqual(result.parameters, [0]);
    assert.deepEqual(result.decode({ _eq_0: "1.25", _eq_1: null, _eq_2: 1.5 }), {
      mean: 1.25,
      native: null,
      integer: 1.5,
    });
    assert.throws(() => result.decode({ _eq_0: Infinity, _eq_1: null, _eq_2: 0 }), /Cannot decode/);
  });

test("FLOAT64 storage, mapped extrema and window/scalar results preserve their contracts", () => {
  const measurement = types.float64().map({
    identity: Symbol("temperature"),
    to: (v: { degrees: number }) => v.degrees,
    from: (degrees) => ({ degrees }),
  });
  const samples = table("samples", {
    id: column(types.integer()),
    value: column(measurement).nullable(),
  });
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ s: samples });
  const order = windowSpec()
    .orderBy(base.scope.s.id.asc())
    .rowsBetween(frame.preceding(1), frame.currentRow);
  const result = base
    .select(({ s }) => ({
      mean: aggregate.avgApprox(s.value).over(order),
      minimum: aggregate.min(s.value).over(order),
    }))
    .compile();
  assert.deepEqual(result.decode({ _eq_0: 1.5, _eq_1: "1" }), {
    mean: 1.5,
    minimum: { degrees: 1 },
  });
  const derived = base.select(({ s }) => ({ mean: aggregate.avgApprox(s.value) })).asCte("means");
  const scalar = db
    .from({ m: derived })
    .select(({ m }) => ({ mean: m.mean }))
    .scalar();
  assert.equal(
    db
      .from({ outer: samples })
      .select(() => ({ scalar }))
      .compile()
      .decode({ _eq_0: "1.5" }).scalar,
    1.5,
  );
  assert.throws(() => aggregate.avgApprox(base.scope.s.value).distinct().over(order), /DISTINCT/);
  assert.throws(
    () =>
      base
        .where(({ s }) => aggregate.avgApprox(s.value).gt(1))
        .select(({ s }) => ({ id: s.id }))
        .compile(),
    /same-level aggregate/,
  );
  assert.throws(() => {
    // @ts-expect-error Precise SUM does not silently switch a float to a decimal accumulator.
    aggregate.sum(base.scope.s.value);
  }, /unsupported SQL type/);
  assert.doesNotThrow(() => aggregate.sum(nativeCast(base.scope.s.value, types.decimal(19, 4))));
  const literal = valueOf(1.5, types.float64()).add(0.25);
  assert.deepEqual(base.select(() => ({ literal })).compile().parameters, [1.5, 0.25]);
});
