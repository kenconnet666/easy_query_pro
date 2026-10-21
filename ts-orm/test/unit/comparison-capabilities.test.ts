import assert from "node:assert/strict";
import test from "node:test";
import {
  aggregate,
  caseWhen,
  coalesce,
  column,
  createDatabase,
  dialect,
  hanaDialect,
  jsonNull,
  mysqlDialect,
  nullIf,
  ParameterLimitError,
  postgresDialect,
  table,
  types,
  valueOf,
  windowSpec,
} from "../../src/index.js";

const json = types.json((value) => value);
const entries = table("entries", {
  id: column(types.integer()),
  document: column(json).nullable(),
});

test("HANA NCLOB JSON rejects ordinary comparisons and grouping before execution", () => {
  const base = createDatabase({ dialect: hanaDialect }).from({ e: entries });
  const value = base.scope.e.document;
  const predicates = [
    value.eq(jsonNull),
    value.ne(jsonNull),
    value.gt(jsonNull),
    value.in([jsonNull]),
    nullIf(value, value).isNull(),
  ];
  for (const predicate of predicates)
    assert.throws(
      () =>
        base
          .where(() => predicate)
          .select(({ e }) => ({ id: e.id }))
          .compile(),
      /NCLOB/,
    );
  assert.throws(
    () =>
      base
        .groupBy(({ e }) => ({ document: e.document }))
        .select((_, __, keys) => ({ document: keys.document }))
        .compile(),
    /NCLOB/,
  );
  assert.throws(
    () =>
      base
        .select(({ e }) => ({ document: e.document }))
        .distinct()
        .compile(),
    /NCLOB/,
  );
  assert.throws(
    () =>
      base
        .orderBy(({ e }) => e.document.asc())
        .select(({ e }) => ({ id: e.id }))
        .compile(),
    /NCLOB/,
  );
  assert.throws(
    () =>
      base
        .select(({ e }) => ({
          count: aggregate.count().over(windowSpec().partitionBy(e.document)),
        }))
        .compile(),
    /NCLOB/,
  );
});

test("MySQL JSON cannot use membership or whole-document sorting implicitly", () => {
  const base = createDatabase({ dialect: mysqlDialect }).from({ e: entries });
  assert.throws(
    () =>
      base
        .where(({ e }) => e.document.in([jsonNull]))
        .select(({ e }) => ({ id: e.id }))
        .compile(),
    /JSON/,
  );
  assert.throws(
    () =>
      base
        .orderBy(({ e }) => e.document.asc())
        .select(({ e }) => ({ id: e.id }))
        .compile(),
    /JSON/,
  );
  assert.doesNotThrow(() =>
    base
      .groupBy(({ e }) => ({ document: e.document }))
      .select((_, __, keys) => ({ document: keys.document }))
      .compile(),
  );
});

test("JSON capability checks survive CASE, COALESCE and CTE projection", () => {
  const db = createDatabase({ dialect: hanaDialect });
  const base = db.from({ e: entries });
  const nullable = valueOf(jsonNull, json.nullable());
  const wrapped = caseWhen(
    base.scope.e.id.gt(0),
    coalesce(base.scope.e.document, nullable),
    nullable,
  );
  const cte = base.select(() => ({ wrapped })).asCte("wrapped_json");
  assert.throws(
    () =>
      db
        .from({ c: cte })
        .where(({ c }) => c.wrapped.eq(jsonNull))
        .select(() => ({ id: valueOf(1, types.integer()) }))
        .compile(),
    /NCLOB/,
  );
});

test("JSON NULL inspection, ordinary reads and UNION ALL remain available", () => {
  for (const dialect of [hanaDialect, mysqlDialect, postgresDialect]) {
    const db = createDatabase({ dialect });
    const base = db.from({ e: entries });
    const first = base
      .where(({ e }) => e.document.isNull())
      .select(({ e }) => ({ document: e.document }));
    assert.doesNotThrow(() =>
      db
        .from({ u: first.unionAll(first) })
        .select(({ u }) => ({ document: u.document }))
        .compile(),
    );
    assert.doesNotThrow(() =>
      base.select(({ e }) => ({ count: aggregate.count(e.document) })).compile(),
    );
  }
});

test("HANA JSON COUNT converts the LOB before aggregation and preserves filter binding order", () => {
  const base = createDatabase({ dialect: dialect("hana", { maxParameters: 2 }) }).from({
    e: entries,
  });
  const query = base
    .select(({ e }) => ({
      count: aggregate.count(valueOf(jsonNull, json)).filter(e.id.gt(2)).over(windowSpec()),
    }))
    .compile();
  assert.match(
    query.sql,
    /COUNT\(CASE WHEN .* THEN LENGTH\(CAST\(\? AS NCLOB\)\) ELSE NULL END\) OVER/,
  );
  assert.deepEqual(query.parameters, [2, "null"]);
  assert.throws(
    () => base.select(({ e }) => ({ count: aggregate.count(e.document).distinct() })).compile(),
    /NCLOB/,
  );
  const smaller = createDatabase({ dialect: dialect("hana", { maxParameters: 1 }) }).from({
    e: entries,
  });
  assert.throws(
    () =>
      smaller
        .select(({ e }) => ({
          count: aggregate.count(valueOf(jsonNull, json)).filter(e.id.gt(2)).over(windowSpec()),
        }))
        .compile(),
    ParameterLimitError,
  );
});
