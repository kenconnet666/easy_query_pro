import test from "node:test";
import assert from "node:assert/strict";
import {
  call,
  column,
  createDatabase,
  hanaDialect,
  mysqlDialect,
  postgresDialect,
  sqlFragment,
  table,
  types,
  WriteExecutionError,
  type Driver,
} from "../../src/index.js";

const records = table("records", {
  id: column(types.integer()).generated(),
  name: column(types.text()),
  note: column(types.text()).nullable(),
  a: column(types.integer()).defaulted(),
  b: column(types.integer()).defaulted(),
});
function recording(failAt = -1) {
  const events: string[] = [];
  let statements = 0;
  const driver: Driver = {
    async acquire() {
      events.push("acquire");
      return {
        async execute(sql) {
          events.push(sql);
          if (statements++ === failAt) throw new Error("database constraint");
          return { rows: [], affectedRows: 1n };
        },
        async begin() {
          events.push("begin");
        },
        async commit() {
          events.push("commit");
        },
        async rollback() {
          events.push("rollback");
        },
        async release() {
          events.push("release");
        },
      };
    },
  };
  return { driver, events };
}

for (const dialect of [postgresDialect, mysqlDialect, hanaDialect]) {
  test(dialect.name + " typed writes preserve defaults, NULL and input order", () => {
    const db = createDatabase({ dialect });
    const write = db.insert(records).values([
      { name: "first", a: 1 },
      { name: "second", note: null },
      { name: "third", a: 3 },
    ]);
    const plan = write.compile();
    assert.equal(plan.length, 3);
    assert.deepEqual(
      plan.flatMap((statement) => statement.parameters),
      ["first", 1, "second", null, "third", 3],
    );
    assert.equal(plan[0]!.sql.includes(dialect.quote("id")), false);
    assert.equal(plan[0]!.sql.includes(dialect.quote("note")), false);
    const update = db
      .update(records)
      .set((row) => ({ a: row.a.add(1) }))
      .where((row) => row.name.eq("first"))
      .compile()[0]!;
    assert.deepEqual(update.parameters, [1, "first"]);
    assert.match(update.sql, / SET /);
    assert.doesNotMatch(update.sql, /target["`]?\./);
    assert.deepEqual(
      db
        .deleteFrom(records)
        .where((row) => row.a.eq(1))
        .compile()[0]!.parameters,
      [1],
    );
  });
}

test("native multi-row VALUES only combines consecutive rows with the same column shape", () => {
  const pg = createDatabase({ dialect: postgresDialect });
  const hana = createDatabase({ dialect: hanaDialect });
  const rows = [
    { name: "first" },
    { name: "second" },
    { name: "third", note: null },
    { name: "fourth" },
  ];
  assert.equal(pg.insert(records).values(rows).compile().length, 3);
  assert.equal(hana.insert(records).values(rows).compile().length, 4);
});

test("invalid write plans are rejected before borrowing a connection", async () => {
  const { driver, events } = recording();
  const db = createDatabase({ dialect: hanaDialect, driver });
  const optional = table("optional", {
    id: column(types.integer()).generated(),
    name: column(types.text()).defaulted(),
  });
  await assert.rejects(
    db
      .insert(optional)
      .values([{ name: "valid" }, {}])
      .execute(),
    /explicit column/,
  );
  await assert.rejects(
    db
      .insert(records)
      .values({ name: "value" })
      .returning((row) => ({ id: row.id }))
      .execute(),
    /RETURNING/,
  );
  await assert.rejects(db.update(records).set({ name: "value" }).execute(), /where\(\)/);
  await assert.rejects(db.deleteFrom(records).execute(), /where\(\)/);
  assert.throws(() => db.update(records).set({}), /assigned column/);
  assert.throws(() => db.insert(records).values({ name: "value", id: 1 } as never), /read-only/);
  assert.throws(() => db.insert(records).values({ name: undefined } as never), /Required insert/);
  assert.throws(() => db.update(records).set({ unknown: 1 } as never), /Unknown write column/);
  assert.deepEqual(await db.insert(records).values([]).execute(), { affectedRows: 0n, rows: [] });
  assert.deepEqual(events, []);
});

test("write expressions validate query ownership, aggregate phase and cross-assignment semantics", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const foreign = db.from({ r: records });
  assert.throws(
    () => db.update(records).set({ a: foreign.scope.r.a }).allRows().compile(),
    /outside this query scope/,
  );
  assert.throws(
    () => db.insert(records).values({ name: foreign.scope.r.name }).compile(),
    /outside this query scope/,
  );
  assert.throws(
    () =>
      db
        .update(records)
        .set((row) => ({ a: row.a.add(1), b: row.a }))
        .allRows()
        .compile(),
    /another column changed/,
  );
  const aggregate = call(
    {
      name: "sum",
      phase: "aggregate",
      render: { postgres: (args) => sqlFragment`SUM(${args[0]!})` },
    },
    types.integer(),
    [foreign.scope.r.a],
  );
  assert.throws(
    () => db.update(records).set({ a: aggregate }).allRows().compile(),
    /same-level aggregate/,
  );
  assert.throws(
    () =>
      db
        .update(records)
        .set({ name: foreign.scope.r.name.nullable() as never })
        .allRows()
        .compile(),
    /incompatible/,
  );
});

test("multi-statement writes borrow once and preserve partial failure context", async () => {
  const { driver, events } = recording(1);
  const db = createDatabase({ dialect: hanaDialect, driver });
  await assert.rejects(
    db
      .insert(records)
      .values([{ name: "a" }, { name: "b" }, { name: "c" }])
      .execute(),
    (error) => {
      assert.ok(error instanceof WriteExecutionError);
      assert.equal(error.statementIndex, 1);
      assert.equal(error.completedStatements, 1);
      assert.equal(error.affectedRows, 1n);
      assert.match(String(error.cause), /database constraint/);
      return true;
    },
  );
  assert.equal(events.filter((event) => event === "acquire").length, 1);
  assert.equal(events.filter((event) => event.startsWith("INSERT")).length, 2);
  assert.equal(events.at(-1), "release");
});

test("batch writes participate in the caller's transaction and escaped writes cannot continue", async () => {
  const { driver, events } = recording(1);
  const db = createDatabase({ dialect: hanaDialect, driver });
  let escaped: typeof db | undefined;
  await assert.rejects(
    db.transaction(async (tx) => {
      escaped = tx;
      await tx
        .insert(records)
        .values([{ name: "a" }, { name: "b" }])
        .execute();
    }),
    WriteExecutionError,
  );
  assert.equal(events.filter((event) => event === "acquire").length, 1);
  assert.deepEqual(events.slice(-2), ["rollback", "release"]);
  assert.throws(() => escaped!.insert(records), /already ended/);
});

test("chained SET cannot target the same physical column through different fields", async () => {
  const { driver, events } = recording();
  const db = createDatabase({ dialect: postgresDialect, driver });
  const aliases = table("aliases", {
    a: column(types.text()).named("shared"),
    b: column(types.text()).named("shared"),
  });
  assert.throws(() => db.update(aliases).set({ a: "x", b: "y" }), /same column/);
  await assert.rejects(
    db.update(aliases).set({ a: "x" }).set({ b: "y" }).allRows().execute(),
    /same physical column/,
  );
  assert.deepEqual(events, []);
});

test("large RETURNING results do not depend on the JavaScript argument-count limit", async () => {
  let released = 0;
  const size = 200_000;
  const db = createDatabase({
    dialect: postgresDialect,
    driver: {
      async acquire() {
        return {
          async execute() {
            return {
              rows: Array.from({ length: size }, (_, i) => ({ _eq_0: i })),
              affectedRows: BigInt(size),
            };
          },
          async begin() {},
          async commit() {},
          async rollback() {},
          async release() {
            released++;
          },
        };
      },
    },
  });
  const result = await db
    .deleteFrom(records)
    .allRows()
    .returning((row) => ({ id: row.id }))
    .execute();
  assert.equal(result.rows.length, size);
  assert.equal(result.rows.at(-1)!.id, size - 1);
  assert.equal(result.affectedRows, BigInt(size));
  assert.equal(released, 1);
});

test("write values are encoded once and remain snapshots when caller objects change", () => {
  let encoded = 0;
  const label = types.text().map({
    identity: Symbol("snapshot-label"),
    to: (value: { text: string }) => {
      encoded++;
      return value.text;
    },
    from: (text) => ({ text }),
  });
  const db = createDatabase({ dialect: postgresDialect });
  const source = { label: { text: "original" } };
  const write = db.insert(table("snapshot", { label: column(label) })).values(source);
  source.label.text = "changed";
  assert.deepEqual(write.compile()[0]!.parameters, ["original"]);
  assert.deepEqual(write.compile()[0]!.parameters, ["original"]);
  assert.equal(encoded, 1);
});
