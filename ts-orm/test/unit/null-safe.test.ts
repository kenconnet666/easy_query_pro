import assert from "node:assert/strict";
import test from "node:test";
import {
  call,
  coalesce,
  column,
  createDatabase,
  DataType,
  dialect,
  hanaDialect,
  jsonNull,
  not,
  postgresDialect,
  sqlFragment,
  table,
  types,
  valueOf,
} from "../../src/index.js";

const entries = table("entries", {
  id: column(types.integer()),
  left: column(types.integer()).nullable(),
  right: column(types.integer()).nullable(),
  text: column(types.text(20)),
});

for (const name of ["postgres", "mysql", "hana"] as const)
  test(`${name} NULL-safe comparisons preserve non-null results and actual binding order`, () => {
    const db = createDatabase({ dialect: dialect(name, { maxParameters: 2 }) });
    const base = db.from({ e: entries });
    const left = valueOf(3, types.integer()).add(2);
    const equal = left.eqNullSafe(base.scope.e.left);
    const result = base.select(() => ({ negated: not(equal) })).compile();
    assert.deepEqual(result.parameters, [3, 2]);
    assert.equal(result.decode({ _eq_0: "true" }).negated, true);
    assert.throws(() => result.decode({ _eq_0: null }), /Cannot decode/);
    const sql = result.sql;
    assert.match(
      sql,
      name === "postgres" ? /IS NOT DISTINCT FROM/ : name === "mysql" ? /<=>/ : /MAP\(/,
    );
    if (name === "hana") {
      assert.equal((sql.match(/MAP\(/g) ?? []).length, 1);
      assert.doesNotMatch(sql, /WHEN NOT/);
    }
    assert.doesNotThrow(() =>
      base
        .select(({ e }) => ({ null: e.id.eqNullSafe(null), different: e.id.neNullSafe(null) }))
        .compile(),
    );
  });

test("NULL-safe comparisons retain codec validation and do not reinterpret undefined", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ e: entries });
  assert.throws(() => {
    // @ts-expect-error Undefined is omission, never SQL NULL.
    base.scope.e.id.eqNullSafe(undefined);
  }, /undefined/);
  assert.doesNotThrow(() =>
    base
      .select(({ e }) => ({ value: e.text.eqNullSafe(valueOf("longer", types.text(40))) }))
      .compile(),
  );
  const one = types
    .integer()
    .map({ identity: Symbol("one"), to: (v: { n: number }) => v.n, from: (n) => ({ n }) });
  const two = types
    .integer()
    .map({ identity: Symbol("two"), to: (v: { n: number }) => v.n, from: (n) => ({ n }) });
  assert.throws(
    () => valueOf({ n: 1 }, one).eqNullSafe(valueOf({ n: 1 }, two)),
    /codec identities/,
  );
  const opaque = new DataType<string, "pg:opaque">(
    {
      kind: "pg:opaque",
      native: {
        identity: Symbol("opaque"),
        dialects: { postgres: { parameter: ["CAST(", " AS TEXT)"] } },
      },
    },
    types.text().codec,
  );
  assert.throws(
    () => base.select(() => ({ value: valueOf("text", opaque).eqNullSafe(null) })).compile(),
    /equality/,
  );
});

test("HANA uses three-valued rendering for a user predicate even when its declared reader is non-null", () => {
  const base = createDatabase({ dialect: hanaDialect }).from({ e: entries });
  const lied = call(
    {
      name: "custom-predicate",
      phase: "row",
      syntax: "predicate",
      render: { hana: (args) => sqlFragment`${args[0]!} = ${args[1]!}` },
    },
    types.boolean(),
    [valueOf(null, types.integer().nullable()), valueOf(1, types.integer())],
  );
  const compiled = base.select(() => ({ value: not(lied) })).compile();
  assert.match(compiled.sql, /WHEN NOT/);
  assert.deepEqual(compiled.parameters, [null, 1, null, 1]);
  assert.throws(() => compiled.decode({ _eq_0: null }), /Cannot decode/);
});

test("NULL-safe binary semantics remain valid in ROLLUP and unsupported JSON cannot hide behind COALESCE", () => {
  const db = createDatabase({ dialect: hanaDialect });
  const base = db.from({ e: entries });
  assert.doesNotThrow(() =>
    base
      .rollupBy(({ e }) => ({ text: e.text }))
      .select((_, __, keys) => ({ total: keys.text.eqNullSafe(null) }))
      .compile(),
  );
  const json = types.json((value) => value).nullable();
  const documents = table("documents", { value: column(json) });
  assert.throws(
    () =>
      db
        .from({ d: documents })
        .select(({ d }) => ({ equal: coalesce(d.value, valueOf(jsonNull, json)).eqNullSafe(null) }))
        .compile(),
    /NCLOB/,
  );
});
