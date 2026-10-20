import test from "node:test";
import assert from "node:assert/strict";
import {
  call,
  column,
  createDatabase,
  dialect,
  ParameterLimitError,
  postgresDialect,
  sql,
  sqlFragment,
  table,
  types,
  valueOf,
  type Dialect,
  type Driver,
} from "../../src/index.js";

const records = table("records", { id: column(types.integer()), label: column(types.text()) });
function neverAcquired() {
  let acquired = 0;
  const driver: Driver = {
    async acquire() {
      acquired++;
      throw new Error("Unexpected acquire");
    },
  };
  return { driver, count: () => acquired };
}

test("actual render occurrences share one budget across CTE, HANA predicates and MySQL ordering", () => {
  const hana = createDatabase({ dialect: dialect("hana", { maxParameters: 1 }) });
  assert.throws(
    () =>
      hana
        .from({ r: records })
        .select(({ r }) => ({ match: r.id.eq(7) }))
        .compile(),
    ParameterLimitError,
  );
  const mysql = createDatabase({ dialect: dialect("mysql", { maxParameters: 1 }) });
  assert.throws(
    () =>
      mysql
        .from({ r: records })
        .orderBy(() => valueOf(7, types.integer()).asc("last"))
        .select(({ r }) => ({ id: r.id }))
        .compile(),
    ParameterLimitError,
  );
  const db = createDatabase({ dialect: dialect("postgres", { maxParameters: 2 }) });
  const cte = db
    .from({ r: records })
    .where(({ r }) => r.id.eq(1))
    .select(({ r }) => ({ id: r.id }))
    .asCte("first");
  assert.throws(
    () =>
      db
        .from({ c: cte })
        .where(({ c }) => c.id.eq(2))
        .select(() => ({ value: valueOf(3, types.integer()) }))
        .compile(),
    (error) => error instanceof ParameterLimitError && error.limit === 2 && error.attempted === 3,
  );
});

test("batch splitting preserves input order and charges RETURNING fixed parameters on each statement", () => {
  const db = createDatabase({ dialect: dialect("postgres", { maxParameters: 5 }) });
  const rows = Array.from({ length: 5 }, (_, index) => ({
    id: index + 1,
    label: `row-${index + 1}`,
  }));
  const plan = db
    .insert(records)
    .values(rows)
    .returning((row) => ({ id: row.id, marker: valueOf("fixed", types.text()) }))
    .compile();
  assert.equal(plan.length, 3);
  assert.ok(plan.every((statement) => statement.parameters.length <= 5));
  assert.deepEqual(
    plan.flatMap((statement) => statement.parameters.slice(0, -1)),
    rows.flatMap((row) => [row.id, row.label]),
  );
  for (const statement of plan) {
    assert.equal(statement.parameters.at(-1), "fixed");
    assert.match(statement.sql, /\$1/);
    assert.ok(!statement.sql.includes("$6"));
  }
});

test("equal column shapes may bind different counts and must be split by the renderer", () => {
  const db = createDatabase({ dialect: dialect("mysql", { maxParameters: 4 }) });
  const repeated = call(
    {
      name: "repeat",
      phase: "row",
      render: { mysql: (args) => sqlFragment`CONCAT(${args[0]!}, ${args[0]!}, ${args[0]!})` },
    },
    types.text(),
    [valueOf("b", types.text())],
  );
  const plan = db
    .insert(records)
    .values([
      { id: 1, label: "a" },
      { id: 2, label: repeated },
      { id: 3, label: "c" },
    ])
    .compile();
  assert.deepEqual(
    plan.map((statement) => statement.parameters),
    [
      [1, "a"],
      [2, "b", "b", "b"],
      [3, "c"],
    ],
  );
});

test("an oversized late row rejects the whole plan before acquisition and other statements are not split", async () => {
  const borrowed = neverAcquired();
  const db = createDatabase({
    dialect: dialect("postgres", { maxParameters: 2 }),
    driver: borrowed.driver,
  });
  const repeated = call(
    {
      name: "repeat",
      phase: "row",
      render: { postgres: (args) => sqlFragment`CONCAT(${args[0]!}, ${args[0]!})` },
    },
    types.text(),
    [valueOf("private-value", types.text())],
  );
  await assert.rejects(
    db
      .insert(records)
      .values([
        { id: 1, label: "small" },
        { id: 2, label: repeated },
      ])
      .execute(),
    (error) => error instanceof ParameterLimitError && !error.message.includes("private-value"),
  );
  await assert.rejects(
    db
      .update(records)
      .set({ label: "changed" })
      .where((r) => r.id.in([1, 2]))
      .execute(),
    ParameterLimitError,
  );
  await assert.rejects(
    db
      .deleteFrom(records)
      .where((r) => r.id.in([1, 2, 3]))
      .execute(),
    ParameterLimitError,
  );
  await assert.rejects(
    db.command(
      sql`SELECT ${valueOf(1, types.integer())}, ${valueOf(2, types.integer())}, ${valueOf(3, types.integer())}`,
    ),
    ParameterLimitError,
  );
  assert.equal(borrowed.count(), 0);
});

test("custom dialect budgets are validated, with a compatible default for older definitions", () => {
  for (const value of [0, -1, 1.5, NaN, 65_536]) {
    assert.throws(() => dialect("postgres", { maxParameters: value }), /maxParameters/);
    const custom: Dialect = { ...postgresDialect, maxParameters: value };
    assert.throws(
      () =>
        createDatabase({ dialect: custom })
          .from({ r: records })
          .select(({ r }) => ({ id: r.id }))
          .compile(),
      /maxParameters/,
    );
  }
  const { maxParameters: _removed, ...legacy } = postgresDialect;
  assert.doesNotThrow(() =>
    createDatabase({ dialect: legacy })
      .from({ r: records })
      .select(({ r }) => ({ id: r.id }))
      .compile(),
  );
});

test("the 65535 binding boundary is checked before the driver can truncate protocol counts", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const values = Array.from({ length: 65_536 }, (_, index) => index);
  const base = db.from({ r: records }).select(({ r }) => ({ id: r.id }));
  const compiled = base.where(({ r }) => r.id.in(values.slice(0, 65_535))).compile();
  assert.equal(compiled.parameters.length, 65_535);
  assert.match(compiled.sql, /\$65535/);
  assert.throws(
    () => base.where(({ r }) => r.id.in(values)).compile(),
    (error) => error instanceof ParameterLimitError && error.attempted === 65_536,
  );
});
