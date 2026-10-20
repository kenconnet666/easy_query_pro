import assert from "node:assert/strict";
import test from "node:test";
import {
  aggregate,
  call,
  coalesce,
  column,
  createDatabase,
  dialect,
  grouping,
  nativeCast,
  postgresDialect,
  sqlFragment,
  table,
  types,
  valueOf,
  windowSpec,
} from "../../src/index.js";

const country = types.text(20).map({
  identity: Symbol("country"),
  to: (value: { code: string }) => value.code,
  from: (code) => ({ code }),
});
const sales = table("sales", {
  country: column(country),
  city: column(types.text(20)).nullable(),
  amount: column(types.decimal(19, 4)).nullable(),
});

for (const name of ["postgres", "mysql", "hana"] as const)
  test(`${name} ROLLUP keeps nullable scope, grouping flags and codecs`, () => {
    const base = createDatabase({ dialect: dialect(name) }).from({ s: sales });
    const report = base
      .rollupBy(({ s }) => ({ country: s.country, city: s.city }))
      .select(({ s }, _, keys) => ({
        country: keys.country,
        same: s.country,
        city: keys.city,
        countryGroup: grouping(keys.country),
        cityGroup: grouping(keys.city),
        count: aggregate.count(),
      }));
    const compiled = report.compile();
    assert.match(compiled.sql, name === "mysql" ? /WITH ROLLUP/ : /GROUP BY ROLLUP \(/);
    assert.equal(compiled.sql.includes("COUNT(*) > 0"), name === "postgres");
    assert.deepEqual(
      compiled.decode({
        _eq_0: null,
        _eq_1: null,
        _eq_2: null,
        _eq_3: "1",
        _eq_4: "1",
        _eq_5: "5",
      }),
      { country: null, same: null, city: null, countryGroup: 1, cityGroup: 1, count: 5n },
    );
    assert.deepEqual(
      compiled.decode({ _eq_0: "A", _eq_1: "A", _eq_2: null, _eq_3: "0", _eq_4: "0", _eq_5: "1" })
        .country,
      { code: "A" },
    );
    assert.equal(base.scope.s.country.type.acceptsNull, false);
    assert.doesNotMatch(base.select(({ s }) => ({ country: s.country })).compile().sql, /ROLLUP/);
  });

test("ROLLUP rejects old non-null projections and supports explicit null-aware transformations", () => {
  const base = createDatabase({ dialect: postgresDialect }).from({ s: sales });
  const old = base.scope.s.country;
  const upper = old.upper();
  const rollup = base
    .select(({ s }) => ({ country: s.country }))
    .rollupBy(({ s }) => ({ country: s.country }));
  assert.throws(() => rollup.select(() => ({ old })).compile(), /preserve subtotal NULL/);
  assert.throws(() => rollup.select(() => ({ upper })).compile(), /preserve subtotal NULL/);
  assert.doesNotThrow(() =>
    rollup
      .select(({ s }, _, keys) => ({
        missing: keys.country.isNull(),
        label: coalesce(keys.country, valueOf({ code: "TOTAL" }, country)),
        upper: s.country.upper(),
      }))
      .compile(),
  );
  assert.throws(() => {
    // @ts-expect-error ROLLUP resets any previous projection and requires a new select.
    rollup.compile();
  }, /Select explicit/);
  assert.throws(
    () => base.rollupBy(({ s }) => ({ computed: s.country.upper() })),
    /projected columns/,
  );
});

test("GROUPING is restricted to the current ROLLUP key and valid query clauses", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ s: sales });
  const rollup = base.rollupBy(({ s }) => ({ country: s.country }));
  assert.throws(
    () => base.select(({ s }) => ({ flag: grouping(s.country) })).compile(),
    /this ROLLUP/,
  );
  assert.throws(
    () => rollup.select(({ s }) => ({ flag: grouping(s.city) })).compile(),
    /this ROLLUP/,
  );
  assert.throws(
    () =>
      rollup
        .where(({ s }) => grouping(s.country).eq(1))
        .select(() => ({ count: aggregate.count() }))
        .compile(),
    /same-level aggregate/,
  );
  assert.throws(
    () => rollup.select(({ s }) => ({ count: aggregate.sum(grouping(s.country)) })).compile(),
    /same-level aggregate/,
  );
  assert.throws(
    () =>
      rollup
        .select(({ s }) => ({
          count: aggregate.count().over(windowSpec().partitionBy(grouping(s.country))),
        }))
        .compile(),
    /grouped results/,
  );
  assert.doesNotThrow(() =>
    rollup
      .having((_, __, keys) => grouping(keys.country).eq(1))
      .orderBy((_, __, keys) => grouping(keys.country).asc())
      .select((_, __, keys) => ({ country: keys.country }))
      .compile(),
  );
  const reset = rollup
    .groupBy(({ s }) => ({ country: s.country }))
    .select((_, __, keys) => ({ country: keys.country }));
  assert.doesNotMatch(reset.compile().sql, /ROLLUP/);
});

test("ROLLUP keys are snapshotted once and declared GROUPING values can be summed after a CTE", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ s: sales });
  let reads = 0;
  const rollup = base
    .rollupBy(({ s }) => ({
      get country() {
        reads++;
        return s.country;
      },
    }))
    .select((_, __, keys) => ({ country: keys.country, flag: grouping(keys.country) }))
    .asCte("rollup_rows");
  const total = db
    .from({ r: rollup })
    .select(({ r }) => ({ flags: aggregate.sum(r.flag) }))
    .compile();
  assert.equal(reads, 1);
  assert.match(total.sql, /SUM\(CAST\(/);
  const hidden = call(
    {
      name: "hidden-group-key",
      phase: "row",
      render: { postgres: () => sqlFragment`UPPER(${base.scope.s.country.node})` },
    },
    types.text(20),
    [],
  );
  assert.throws(
    () =>
      base
        .rollupBy(({ s }) => ({ country: s.country }))
        .select(() => ({ hidden }))
        .compile(),
    /expression arguments/,
  );
});

test("nullable calls and casts cannot hide a subtotal NULL behind an old non-null fallback", () => {
  const base = createDatabase({ dialect: postgresDialect }).from({ s: sales });
  const rollup = base.rollupBy(({ s }) => ({ country: s.country }));
  const maybe = call(
    { name: "nullable-value", phase: "row", render: { postgres: () => sqlFragment`NULL` } },
    country.nullable(),
    [],
  );
  const converted = nativeCast(valueOf({ code: "A" }, country), country);
  for (const nullable of [maybe, converted])
    assert.throws(
      () => rollup.select(() => ({ label: coalesce(nullable, base.scope.s.country) })).compile(),
      /preserve subtotal NULL/,
    );
});

test("renderers must declare GROUPING nodes so clause validation sees their phase", () => {
  const base = createDatabase({ dialect: postgresDialect }).from({ s: sales });
  const rolled = base.rollupBy(({ s }) => ({ country: s.country }));
  const marker = grouping(rolled.groupKeys.country);
  const hidden = call(
    { name: "hidden-grouping", phase: "row", render: { postgres: () => marker.node } },
    types.integer(),
    [rolled.groupKeys.country],
  );
  assert.throws(
    () =>
      rolled
        .where(() => hidden.eq(1))
        .select(() => ({ count: aggregate.count() }))
        .compile(),
    /expression arguments/,
  );
  const declared = call(
    {
      name: "visible-grouping",
      phase: "row",
      render: { postgres: (args) => sqlFragment`${args[0]!}` },
    },
    types.integer(),
    [marker],
  );
  assert.throws(
    () =>
      rolled
        .where(() => declared.eq(1))
        .select(() => ({ count: aggregate.count() }))
        .compile(),
    /same-level aggregate/,
  );
  assert.doesNotThrow(() => rolled.select(() => ({ declared })).compile());
});
