import test from "node:test";
import assert from "node:assert/strict";
import {
  aggregate,
  caseWhen,
  column,
  createDatabase,
  frame,
  hanaDialect,
  mysqlDialect,
  postgresDialect,
  table,
  types,
  valueOf,
  windowFunctions,
  windowSpec,
} from "../../src/index.js";

const events = table("events", {
  id: column(types.integer()),
  value: column(types.integer()).nullable(),
  team: column(types.text()),
});
for (const dialect of [postgresDialect, mysqlDialect, hanaDialect]) {
  test(dialect.name + " windows bind arguments, partitions and ordering in SQL order", () => {
    const db = createDatabase({ dialect });
    const base = db.from({ e: events });
    const spec = windowSpec()
      .partitionBy(valueOf(7, types.integer()))
      .orderBy(base.scope.e.value.add(2).asc("last"));
    const query = base
      .select(({ e }) => ({ lag: windowFunctions.lag(e.value, 3, 999).over(spec) }))
      .where(({ e }) => e.id.gt(8));
    assert.deepEqual(
      query.compile().parameters,
      dialect.name === "mysql" ? [999, 7, 2, 2, 8] : [999, 7, 2, 8],
    );
    assert.match(query.compile().sql, /LAG\(.*?, 3, /);
    assert.equal(query.compile().sql.includes("ROWS"), false);
    assert.deepEqual(query.compile().decode({ _eq_0: null }), { lag: null });
  });
}

test("ROWS boundaries and function-specific constraints fail before execution", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ e: events });
  const spec = windowSpec().orderBy(base.scope.e.id.asc());
  for (const invalid of [-1, 0.5, NaN, Infinity, 2147483648]) {
    assert.throws(() => frame.preceding(invalid), /Window offset/);
    assert.throws(() => windowFunctions.lag(base.scope.e.value, invalid), /Window offset/);
    assert.throws(() => windowFunctions.nthValue(base.scope.e.value, invalid), /Window offset/);
  }
  assert.throws(() => windowFunctions.nthValue(base.scope.e.value, 0), /Window offset/);
  assert.equal(frame.preceding(0), frame.currentRow);
  assert.equal(frame.following(0), frame.currentRow);
  assert.throws(() => spec.rowsBetween(frame.preceding(7), frame.preceding(8)), /ordering/);
  assert.throws(
    () => spec.rowsBetween(frame.unboundedFollowing as never, frame.currentRow),
    /ordering/,
  );
  assert.throws(
    () => aggregate.count().over(windowSpec().rowsBetween(frame.currentRow, frame.currentRow)),
    /requires ORDER BY/,
  );
  const rows = spec.rowsBetween(frame.unboundedPreceding, frame.currentRow);
  assert.throws(() => windowFunctions.rank().over(windowSpec()), /requires ORDER BY/);
  assert.throws(
    () => windowFunctions.lag(base.scope.e.value).over(rows),
    /does not accept a frame/,
  );
  assert.throws(() => windowFunctions.rowNumber().over(rows), /does not accept a frame/);
  assert.throws(
    () => aggregate.count(base.scope.e.id).distinct().over(spec),
    /do not support DISTINCT/,
  );
  assert.doesNotThrow(() => windowFunctions.rowNumber().over(windowSpec()));
  assert.doesNotThrow(() => aggregate.count().over(windowSpec()));
});

test("window definitions snapshot orders and branch without changing prior expressions", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ e: events });
  const order = { expression: base.scope.e.id.node, direction: "ASC" as const };
  const initial = windowSpec().orderBy(order);
  const bounded = initial.rowsBetween(frame.currentRow, frame.following(1));
  order.expression = base.scope.e.value.node;
  const plain = base.select(() => ({ value: aggregate.count().over(initial) })).compile().sql;
  const framed = base.select(() => ({ value: aggregate.count().over(bounded) })).compile().sql;
  assert.match(plain, /ORDER BY "e"\."id" ASC\)/);
  assert.equal(plain.includes("ROWS"), false);
  assert.match(framed, /ROWS BETWEEN CURRENT ROW AND 1 FOLLOWING/);
});

test("window phases and foreign relations cannot be hidden inside other expressions", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ e: events });
  const spec = windowSpec().orderBy(base.scope.e.id.asc());
  const rank = windowFunctions.rank().over(spec);
  const hidden = caseWhen(
    rank.gt(1n),
    valueOf(true, types.boolean()),
    valueOf(false, types.boolean()),
  );
  assert.throws(
    () =>
      base
        .where(() => hidden)
        .select(({ e }) => ({ id: e.id }))
        .compile(),
    /same-level window/,
  );
  assert.throws(
    () => base.select(() => ({ value: windowFunctions.lag(rank).over(spec) })).compile(),
    /same-level window/,
  );
  assert.throws(
    () =>
      base
        .select(({ e }) => ({
          value: aggregate.sum(e.value).over(spec.partitionBy(aggregate.count())),
        }))
        .compile(),
    /same-level aggregate/,
  );
  assert.throws(
    () =>
      base
        .groupBy(({ e }) => ({ team: e.team }))
        .select(() => ({ rank }))
        .compile(),
    /grouped results/,
  );
  const foreign = db.from({ other: events });
  assert.throws(
    () =>
      base
        .select(({ e }) => ({
          value: windowFunctions
            .lag(e.value)
            .over(windowSpec().orderBy(foreign.scope.other.id.asc())),
        }))
        .compile(),
    /outside this query scope/,
  );
});

test("window outputs retain business codecs and conservative numeric origins", () => {
  const type = types.text().map({
    identity: Symbol("label"),
    to: (value: { text: string }) => value.text,
    from: (text) => ({ text }),
  });
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({
    e: table("labels", { id: column(types.integer()), label: column(type).nullable() }),
  });
  const spec = windowSpec().orderBy(base.scope.e.id.asc());
  const compiled = base
    .select(({ e }) => ({ label: windowFunctions.lag(e.label, 1, { text: "default" }).over(spec) }))
    .compile();
  assert.deepEqual(compiled.parameters, ["default"]);
  assert.deepEqual(compiled.decode({ _eq_0: "decoded" }), { label: { text: "decoded" } });
  assert.deepEqual(compiled.decode({ _eq_0: null }), { label: null });
  const valueBase = db.from({ e: events });
  const valueSpec = windowSpec().orderBy(valueBase.scope.e.id.asc());
  const shifted = valueBase.select(({ e }) => ({
    value: windowFunctions.lag(e.value, 1, e.value.add(1)).over(valueSpec),
  }));
  assert.throws(() => aggregate.sum(db.from({ d: shifted.as() }).scope.d.value), /nativeCast/);
  const summed = valueBase.select(({ e }) => ({ value: aggregate.sum(e.value).over(valueSpec) }));
  const outer = db
    .from({ d: summed.as() })
    .select(({ d }) => ({ total: aggregate.sum(d.value) }))
    .compile();
  assert.equal((outer.sql.match(/AS DECIMAL\(38,0\)/g) ?? []).length, 1);
});

test("ordinary NULLS ordering shares the window dialect rules and repeated bindings", () => {
  const db = createDatabase({ dialect: mysqlDialect });
  const base = db.from({ e: events });
  const query = base
    .orderBy(({ e }) => e.value.add(2).desc("first"))
    .select(({ e }) => ({ id: e.id }))
    .compile();
  assert.deepEqual(query.parameters, [2, 2]);
  assert.match(query.sql, /CASE WHEN .* IS NULL THEN 0 ELSE 1 END ASC, .* DESC/);
});
