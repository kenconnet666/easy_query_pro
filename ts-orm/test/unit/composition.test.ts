import test from "node:test";
import assert from "node:assert/strict";
import {
  call,
  column,
  createDatabase,
  DataType,
  hanaDialect,
  mysqlDialect,
  not,
  postgresDialect,
  sqlFragment,
  table,
  types,
  valueOf,
} from "../../src/index.js";

const users = table("users", { id: column(types.integer()), name: column(types.text()) });

for (const dialect of [postgresDialect, mysqlDialect, hanaDialect]) {
  test(dialect.name + " CTE dependencies and repeated references share one definition", () => {
    const db = createDatabase({ dialect });
    const a = db
      .from({ u: users })
      .where(({ u }) => u.id.gt(7))
      .select(({ u }) => ({ id: u.id, name: u.name }))
      .asCte("eligible");
    const b = db
      .from({ a })
      .where(({ a }) => a.id.lt(90))
      .select(({ a }) => ({ id: a.id }))
      .asCte("bounded");
    const query = db
      .from({ left: b })
      .innerJoin({ right: b }, ({ left, right }) => left.id.eq(right.id))
      .select(({ left }) => ({ id: left.id.add(10) }));
    const compiled = query.compile();
    assert.deepEqual(compiled.parameters, [7, 90, 10]);
    assert.equal(compiled.sql.split(`${dialect.quote("eligible")} AS (`).length - 1, 1);
    assert.equal(compiled.sql.split(`${dialect.quote("bounded")} AS (`).length - 1, 1);
    assert.ok(
      compiled.sql.indexOf(dialect.quote("eligible")) <
        compiled.sql.indexOf(dialect.quote("bounded")),
    );
    assert.deepEqual(compiled.decode({ _eq_0: 18 }), { id: 18 });
  });
}

test("UNION preserves branch bindings while the result gets an independent scope", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ u: users });
  const first = base
    .where(({ u }) => u.id.gt(1))
    .select(({ u }) => ({ id: u.id.add(10) }))
    .limit(2);
  const second = base
    .where(({ u }) => u.id.lt(4))
    .select(({ u }) => ({ id: u.id.add(20) }))
    .limit(1);
  const result = db
    .from({ combined: first.unionAll(second) })
    .where(({ combined }) => combined.id.lt(100))
    .select(({ combined }) => ({ id: combined.id }))
    .limit(1);
  assert.deepEqual(result.compile().parameters, [10, 1, 20, 4, 100]);
  assert.match(result.compile().sql, /UNION ALL/);
  assert.match(result.compile().sql, /LIMIT 2/);
  assert.throws(
    () =>
      db
        .from({ x: first.union(second) })
        .select(() => ({ leaked: base.scope.u.id }))
        .compile(),
    /outside this query scope/,
  );
});

test("UNION rejects reordered fields, incompatible codecs and different storage domains", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ u: users });
  const left = base.select(({ u }) => ({ first: u.name, second: u.name }));
  const reordered = base.select(({ u }) => ({ second: u.name, first: u.name }));
  assert.throws(() => left.unionAll(reordered), /projection order/);
  const long = base.select(() => ({
    first: valueOf("a", types.text(500)),
    second: valueOf("b", types.text(500)),
  }));
  assert.throws(() => left.union(long), /matching SQL storage/);
  const custom = () =>
    types.text().map({
      identity: Symbol("label"),
      to: (v: { label: string }) => v.label,
      from: (label) => ({ label }),
    });
  const customLeft = base.select(() => ({ value: valueOf({ label: "a" }, custom()) }));
  const customRight = base.select(() => ({ value: valueOf({ label: "b" }, custom()) }));
  assert.throws(() => customLeft.unionAll(customRight), /codec identity/);
});

test("UNION merges NULL before custom decoding and keeps the codec through a CTE", () => {
  let decoded = 0;
  const type = types.text().map({
    identity: Symbol("label"),
    to: (v: { label: string }) => v.label,
    from: (label) => {
      decoded++;
      return { label };
    },
  });
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ u: users });
  const first = base.select(() => ({ label: valueOf({ label: "a" }, type) }));
  const second = base.select(() => ({ label: valueOf(null, type.nullable()) }));
  const result = db
    .from({ rows: first.unionAll(second).asCte("labels") })
    .select(({ rows }) => ({ value: rows.label }))
    .compile();
  assert.deepEqual(result.decode({ _eq_0: null }), { value: null });
  assert.equal(decoded, 0);
  assert.deepEqual(result.decode({ _eq_0: "a" }), { value: { label: "a" } });
  assert.equal(decoded, 1);
});

test("shared business mappings do not erase different underlying codecs in UNION", () => {
  const encoded = (prefix: string) =>
    new DataType<string, "text">(
      { kind: "text", length: 255 },
      {
        identity: Symbol(prefix),
        encode: (value) => prefix + value,
        decode: (value) => String(value).slice(prefix.length),
      },
    );
  const mapping = {
    identity: Symbol("label"),
    to: (value: { label: string }) => value.label,
    from: (label: string) => ({ label }),
  };
  const a = encoded("a:");
  const b = encoded("bbbb:");
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ u: users });
  const left = base.select(() => ({ value: valueOf({ label: "x" }, a.map(mapping)) }));
  const right = base.select(() => ({ value: valueOf({ label: "x" }, b.map(mapping)) }));
  assert.throws(() => left.unionAll(right), /codec identity/);
  const same = base.select(() => ({ value: valueOf({ label: "x" }, a.map(mapping).nullable()) }));
  assert.doesNotThrow(() => left.unionAll(same));
});

test("CTE definitions reject ambiguous names and client ownership", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const a = db.from({ u: users }).select(({ u }) => ({ id: u.id }));
  const first = a.asCte("selected");
  const second = a.asCte("SELECTED");
  assert.throws(
    () =>
      db
        .from({ a: first, b: second })
        .select(({ a }) => ({ id: a.id }))
        .compile(),
    /different definitions/,
  );
  assert.throws(
    () =>
      db
        .from({ a: a.asCte("users") })
        .select(({ a }) => ({ id: a.id }))
        .compile(),
    /shadows a referenced table/,
  );
  const foreign = createDatabase({ dialect: postgresDialect });
  assert.throws(() => foreign.from({ a: first }), /cross database clients/);
  const foreignQuery = foreign.from({ u: users }).select(({ u }) => ({ id: u.id }));
  assert.throws(() => a.unionAll(foreignQuery), /cross database clients/);
});

test("count places WITH outside the counted query and retains branch limits", async () => {
  let executed = "";
  let bindings: readonly unknown[] = [];
  const db = createDatabase({
    dialect: postgresDialect,
    driver: {
      async acquire() {
        return {
          async execute(sql, parameters) {
            executed = sql;
            bindings = parameters;
            return { rows: [{ _eq_count: "2" }], affectedRows: 0n };
          },
          async begin() {},
          async commit() {},
          async rollback() {},
          async release() {},
        };
      },
    },
  });
  const cte = db
    .from({ u: users })
    .where(({ u }) => u.id.gt(7))
    .select(({ u }) => ({ id: u.id }))
    .asCte("eligible");
  const selected = db
    .from({ x: cte })
    .select(({ x }) => ({ id: x.id }))
    .limit(2);
  assert.equal(await selected.count(), 2n);
  assert.match(executed, /^WITH .* SELECT COUNT\(\*\).*LIMIT 2/);
  assert.equal(executed.includes("FROM (WITH"), false);
  assert.deepEqual(bindings, [7]);
});

test("HANA distinguishes predicate syntax from scalar BOOLEAN values and preserves all bindings", () => {
  const db = createDatabase({ dialect: hanaDialect });
  const base = db.from({ u: users });
  const predicate = call(
    {
      name: "positive",
      phase: "row",
      syntax: "predicate",
      render: { hana: (args) => sqlFragment`(${args[0]!} > ${args[1]!})` },
    },
    types.boolean().nullable(),
    [base.scope.u.id, valueOf(3, types.integer())],
  );
  const compiled = base
    .where(() => valueOf(true, types.boolean()))
    .select(() => ({ positive: predicate }))
    .compile();
  assert.deepEqual(compiled.parameters, [3, 3, true]);
  assert.match(compiled.sql, /CASE WHEN .* THEN TRUE WHEN NOT .* THEN FALSE ELSE NULL END/);
  assert.match(compiled.sql, /WHERE \(CAST\(\? AS BOOLEAN\) = TRUE\)/);
  assert.deepEqual(compiled.decode({ _eq_0: null }), { positive: null });
  assert.deepEqual(
    base
      .where(() => predicate)
      .select(({ u }) => ({ id: u.id }))
      .compile().parameters,
    [3],
  );
});

test("native composite renderers remain one operand under NOT and multiplication", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ u: users });
  const predicate = call(
    {
      name: "composite",
      phase: "row",
      syntax: "predicate",
      render: { postgres: (args) => sqlFragment`${args[0]!} > 0 OR ${args[0]!} IS NULL` },
    },
    types.boolean().nullable(),
    [base.scope.u.id],
  );
  const sum = call(
    {
      name: "sum-pair",
      phase: "row",
      render: { postgres: (args) => sqlFragment`${args[0]!} + ${args[0]!}` },
    },
    types.integer(),
    [base.scope.u.id],
  );
  const compiled = base
    .select(() => ({ negated: not(predicate), doubled: sum.multiply(2) }))
    .compile();
  assert.match(compiled.sql, /NOT \("u"\."id" > 0 OR "u"\."id" IS NULL\)/);
  assert.match(compiled.sql, /\("u"\."id" \+ "u"\."id"\) \* CAST/);
});
