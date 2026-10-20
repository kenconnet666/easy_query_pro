import assert from "node:assert/strict";
import test from "node:test";
import {
  aggregate,
  and,
  call,
  column,
  createDatabase,
  dialect,
  jsonNull,
  not,
  postgresDialect,
  sqlFragment,
  table,
  types,
  valueOf,
  type Expression,
} from "../../src/index.js";

const money = types.decimal(19, 4).map({
  identity: Symbol("subquery-money"),
  to: (v: { amount: string }) => v.amount,
  from: (amount) => ({ amount }),
});
const users = table("users", {
  id: column(types.integer()),
  parentId: column(types.integer()).nullable(),
  amount: column(money),
});

for (const name of ["postgres", "mysql", "hana"] as const)
  test(`${name} expression subqueries share bindings and avoid correlated alias shadowing`, () => {
    const db = createDatabase({ dialect: dialect(name) });
    const base = db.from({ u: users });
    const query = base
      .where(({ u }) => u.id.gt(1))
      .select(({ u }) => ({
        id: u.id,
        has: db
          .from({ u: users })
          .where(({ u: child }) => and(child.parentId.eq(u.id), child.id.gt(7)))
          .select(({ u: child }) => ({ id: child.id }))
          .exists(),
        count: db
          .from({ c: users })
          .where(({ c }) => and(c.parentId.eq(u.id), c.id.gt(8)))
          .select(() => ({ count: aggregate.count() }))
          .scalar(),
      }));
    const compiled = query.compile();
    assert.deepEqual(compiled.parameters, [7, 8, 1]);
    assert.match(compiled.sql, /_eq_subquery_1/);
    assert.equal((compiled.sql.match(/EXISTS \(/g) ?? []).length, 1);
    assert.match(compiled.sql, /COUNT\(\*\)/);
    assert.deepEqual(compiled.decode({ _eq_0: "1", _eq_1: 1, _eq_2: "2" }), {
      id: 1,
      has: true,
      count: 2n,
    });
    assert.equal(query.compile().sql, compiled.sql);
    assert.deepEqual(query.compile().parameters, compiled.parameters);
    assert.equal(
      (
        base
          .select(({ u }) => ({ id: u.id }))
          .compile()
          .sql.match(/WHERE/g) ?? []
      ).length,
      0,
    );
  });

test("EXISTS preserves aggregate, distinct, offset and limit-zero semantics", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const counted = db
    .from({ c: users })
    .where(({ c }) => c.id.lt(0))
    .select(() => ({ count: aggregate.count() }));
  const distinct = db
    .from({ d: users })
    .select(({ d }) => ({ parent: d.parentId }))
    .distinct()
    .limit(1, 1);
  const empty = db
    .from({ e: users })
    .select(({ e }) => ({ id: e.id }))
    .limit(0);
  const sql = db
    .from({ u: users })
    .select(() => ({ count: counted.exists(), distinct: distinct.exists(), empty: empty.exists() }))
    .compile().sql;
  assert.match(sql, /EXISTS \(SELECT COUNT\(\*\)/);
  assert.match(sql, /EXISTS \(SELECT DISTINCT .* LIMIT 1 OFFSET 1\)/);
  assert.match(sql, /LIMIT 0\)/);
  assert.doesNotMatch(sql, /SELECT 1/);
});

test("scalar preserves codecs and numeric origin without an implicit LIMIT", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const single = db
    .from({ c: users })
    .select(({ c }) => ({ amount: c.amount }))
    .scalar();
  const result = db
    .from({ u: users })
    .select(() => ({ amount: single }))
    .compile();
  assert.doesNotMatch(result.sql, /LIMIT/);
  assert.deepEqual(result.decode({ _eq_0: "12.3400" }), { amount: { amount: "12.3400" } });
  assert.deepEqual(result.decode({ _eq_0: null }), { amount: null });
  const total = db
    .from({ c: users })
    .select(({ c }) => ({ amount: aggregate.sum(c.id) }))
    .scalar();
  assert.doesNotThrow(() =>
    db
      .from({ u: users })
      .select(() => ({ amount: aggregate.sum(total) }))
      .compile(),
  );
  const json = types.json((value) => value);
  const document = db
    .from({ c: users })
    .select(() => ({ value: valueOf(jsonNull, json) }))
    .scalar();
  assert.equal(
    db
      .from({ u: users })
      .select(() => ({ value: document }))
      .compile()
      .decode({ _eq_0: "null" }).value,
    jsonNull,
  );
});

test("subquery shape, owners, reused relation identities and invisible columns fail before execution", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ u: users });
  const foreign = createDatabase({ dialect: postgresDialect })
    .from({ f: users })
    .select(({ f }) => ({ id: f.id }))
    .exists();
  assert.throws(() => base.select(() => ({ foreign })).compile(), /cross database/);
  const invisible = db.from({ other: users }).scope.other.id;
  const illegal = db
    .from({ c: users })
    .where(({ c }) => c.id.eq(invisible))
    .select(({ c }) => ({ id: c.id }))
    .exists();
  assert.throws(() => base.select(() => ({ illegal })).compile(), /outside this query scope/);
  assert.throws(
    () =>
      base.select(() => ({ reused: base.select(({ u }) => ({ id: u.id })).exists() })).compile(),
    /fresh relation/,
  );
  assert.throws(() => {
    // @ts-expect-error The runtime also checks callers that bypass the selected-column contract.
    base.exists();
  }, /Select before/);
  assert.throws(() => {
    // @ts-expect-error A scalar query has exactly one projection slot.
    base.select(({ u }) => ({ id: u.id, parent: u.parentId })).scalar();
  }, /exactly one/);
});

test("CTEs used only inside subqueries are hoisted once with stable parameter order", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const definition = db
    .from({ source: users })
    .where(({ source }) => source.id.gt(10))
    .select(({ source }) => ({ id: source.id, parent: source.parentId }))
    .asCte("children");
  const base = db.from({ u: users });
  const child = db
    .from({ c: definition })
    .where(({ c }) => c.parent.eq(base.scope.u.id))
    .select(({ c }) => ({ id: c.id }))
    .exists();
  const query = base.select(() => ({ child, again: not(child) }));
  const compiled = query.compile();
  assert.equal((compiled.sql.match(/"children" AS/g) ?? []).length, 1);
  assert.deepEqual(compiled.parameters, [10]);
  assert.match(compiled.sql, /^WITH /);
  const other = db
    .from({ source: users })
    .select(({ source }) => ({ id: source.id }))
    .asCte("children");
  const conflict = db
    .from({ c: other })
    .select(({ c }) => ({ id: c.id }))
    .exists();
  assert.throws(() => base.select(() => ({ child, conflict })).compile(), /different definitions/);
  const correlated = db
    .from({ c: users })
    .where(({ c }) => c.id.eq(base.scope.u.id))
    .select(({ c }) => ({ id: c.id }))
    .asCte("correlated");
  const invalid = db
    .from({ c: correlated })
    .select(({ c }) => ({ id: c.id }))
    .exists();
  assert.throws(
    () => base.select(() => ({ invalid })).compile(),
    /outside this query scope|cannot capture/,
  );
});

test("grouping and aggregate ownership remain explicit at query boundaries", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ u: users });
  const count = db
    .from({ c: users })
    .where(({ c }) => c.parentId.eq(base.scope.u.id))
    .select(() => ({ count: aggregate.count() }))
    .scalar();
  assert.throws(
    () =>
      base
        .groupBy(({ u }) => ({ parent: u.parentId }))
        .select((_, __, group) => ({ parent: group.parent, count }))
        .compile(),
    /grouped results/,
  );
  assert.doesNotThrow(() =>
    base
      .where(() => count.gt(0n))
      .groupBy(({ u }) => ({ parent: u.parentId }))
      .select((_, __, group) => ({ parent: group.parent }))
      .compile(),
  );
  const outerAggregate = db
    .from({ c: users })
    .select(() => ({ total: aggregate.sum(base.scope.u.id) }))
    .scalar();
  assert.throws(
    () => base.select(() => ({ total: outerAggregate })).compile(),
    /cannot capture outer/,
  );
  const derived = db
    .from({ c: users })
    .where(({ c }) => c.id.eq(base.scope.u.id))
    .select(({ c }) => ({ id: c.id }))
    .as();
  assert.throws(
    () =>
      db
        .from({ u: users, d: derived })
        .select(({ d }) => ({ id: d.id }))
        .compile(),
    /cannot capture/,
  );
});

test("subquery expansion honors parameter budgets and rejects hidden CTE definitions", () => {
  const db = createDatabase({ dialect: dialect("postgres", { maxParameters: 1 }) });
  const scalar = db
    .from({ c: users })
    .where(({ c }) => c.id.eq(1))
    .select(({ c }) => ({ id: c.id }))
    .scalar();
  assert.throws(
    () =>
      db
        .from({ u: users })
        .select(() => ({ a: scalar, b: scalar }))
        .compile(),
    /parameter budget/,
  );
  const cte = db
    .from({ c: users })
    .select(({ c }) => ({ id: c.id }))
    .asCte("hidden_source");
  const hidden = db
    .from({ c: cte })
    .select(({ c }) => ({ id: c.id }))
    .exists();
  const expanded = call(
    {
      name: "hidden-query",
      phase: "row",
      syntax: "predicate",
      render: { postgres: () => sqlFragment`${hidden.node}` },
    },
    types.boolean(),
    [],
  );
  assert.throws(
    () =>
      db
        .from({ u: users })
        .select(() => ({ expanded }))
        .compile(),
    /expression arguments/,
  );
});

test("HANA correlated ordering and pagination are rejected without rewriting the query", () => {
  const db = createDatabase({ dialect: dialect("hana") });
  const base = db.from({ u: users });
  const child = db
    .from({ c: users })
    .where(({ c }) => c.parentId.eq(base.scope.u.id))
    .select(({ c }) => ({ id: c.id }));
  for (const scalar of [child.limit(1).scalar(), child.orderBy(({ c }) => c.id.asc()).scalar()])
    assert.throws(() => base.select(() => ({ scalar })).compile(), /HANA correlated/);
});

test("a renderer cannot introduce a physical table shadowed by a hoisted CTE", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const accounts = table("accounts", { id: column(types.integer()) });
  const named = db
    .from({ a: accounts })
    .select(({ a }) => ({ id: a.id }))
    .asCte("users");
  const hidden = db
    .from({ u: users })
    .select(({ u }) => ({ id: u.id }))
    .exists();
  const expanded = call(
    {
      name: "hidden-table",
      phase: "row",
      syntax: "predicate",
      render: { postgres: () => hidden.node },
    },
    types.boolean(),
    [],
  );
  assert.throws(
    () =>
      db
        .from({ a: named })
        .select(() => ({ expanded }))
        .compile(),
    /expression arguments/,
  );
});

test("renderers keep subquery grouping dependencies in declared arguments", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ u: users });
  const child = db
    .from({ c: users })
    .where(({ c }) => c.parentId.eq(base.scope.u.id))
    .select(() => ({ count: aggregate.count() }))
    .scalar();
  const hidden = call(
    { name: "hidden-correlation", phase: "row", render: { postgres: () => child.node } },
    child.type,
    [],
  );
  const grouped = base.groupBy(({ u }) => ({ parent: u.parentId }));
  assert.throws(
    () => grouped.select((_, __, keys) => ({ parent: keys.parent, hidden })).compile(),
    /expression arguments/,
  );
  const declared = call(
    {
      name: "declared-query",
      phase: "row",
      render: { postgres: (args) => sqlFragment`COALESCE(${args[0]!}, 0)` },
    },
    child.type,
    [child],
  );
  assert.throws(
    () => grouped.select((_, __, keys) => ({ parent: keys.parent, declared })).compile(),
    /grouped results/,
  );
  assert.doesNotThrow(() => base.select(() => ({ declared })).compile());
});

test("JOIN subqueries cannot reference a future join and writes reject unimplemented query scopes", () => {
  const db = createDatabase({ dialect: postgresDialect });
  let future!: Expression<number, "integer">;
  const deferred = call(
    { name: "future-column", phase: "row", render: { postgres: () => future.node } },
    types.integer(),
    [],
  );
  const joined = db
    .from({ a: users })
    .innerJoin({ b: users }, () =>
      db
        .from({ c: users })
        .where(({ c }) => c.id.eq(deferred))
        .select(({ c }) => ({ id: c.id }))
        .exists(),
    )
    .innerJoin({ later: users }, ({ later }) => later.id.gt(0));
  future = joined.scope.later.id;
  assert.throws(
    () => joined.select(({ a }) => ({ id: a.id })).compile(),
    /outside this query scope|expression arguments/,
  );
  const exists = db
    .from({ c: users })
    .select(({ c }) => ({ id: c.id }))
    .exists();
  assert.throws(
    () =>
      db
        .deleteFrom(users)
        .where(() => exists)
        .compile(),
    /require a SELECT/,
  );
});

test("MySQL keeps distinct pagination below EXISTS and avoids its wrapper alias colliding with an ancestor", () => {
  const db = createDatabase({ dialect: dialect("mysql") });
  const base = db.from({ _eq_exists: users });
  const child = db
    .from({ c: users })
    .where(({ c }) => c.parentId.eq(base.scope._eq_exists.id))
    .select(({ c }) => ({ parent: c.parentId }))
    .distinct()
    .limit(1, 1)
    .exists();
  const sql = base.select(() => ({ child })).compile().sql;
  assert.match(sql, /EXISTS \(SELECT 1 FROM \(SELECT DISTINCT/);
  assert.match(sql, /LIMIT 1 OFFSET 1\) `_eq_exists_1`\)/);
});
