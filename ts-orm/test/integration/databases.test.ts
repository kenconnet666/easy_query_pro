import test from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { Pool as PgPool } from "pg";
import { createPool } from "mysql2/promise";
import {
  call,
  column,
  createDatabase,
  identifier,
  mysqlDialect,
  postgresDialect,
  sql,
  sqlFragment,
  table,
  types,
  valueOf,
  type Dialect,
  type Driver,
  type SqlNode,
} from "../../src/index.js";
import { postgresDriver } from "../../src/drivers/postgres.js";
import { mysqlDriver } from "../../src/drivers/mysql.js";

interface Harness {
  driver: Driver;
  dialect: Dialect;
  close(): Promise<void>;
}
function required(name: string): string {
  const value = process.env[name];
  if (value === undefined) throw new Error("Missing integration-test variable: " + name);
  return value;
}
async function verify(harness: Harness): Promise<void> {
  const { driver, dialect } = harness;
  const db = createDatabase({ dialect, driver });
  const name = "eqts_" + randomBytes(7).toString("hex");
  const definition = table(name, {
    id: column(types.integer()).primaryKey(),
    name: column(types.text()),
    managerId: column(types.integer()).nullable().named("manager_id"),
    amount: column(types.decimal(19, 4)),
    big: column(types.bigint()),
  });
  let created = false;
  try {
    await db.command(
      sql`CREATE TABLE ${identifier(name)} (id INTEGER PRIMARY KEY, name VARCHAR(255), manager_id INTEGER, amount DECIMAL(19,4), big BIGINT)`,
    );
    created = true;
    for (const row of [
      {
        id: 1,
        name: "O'Reilly 中😀",
        managerId: null,
        amount: "123456789012345.1200",
        big: 9007199254740993n,
      },
      { id: 2, name: "child", managerId: 1, amount: "0.0100", big: -9007199254740993n },
      { id: 3, name: "unmatched", managerId: 999, amount: "-2.0000", big: 0n },
    ]) {
      await db.command(sql`INSERT INTO ${identifier(name)} (id, name, manager_id, amount, big) VALUES (
        ${valueOf(row.id, definition.columns.id.type)}, ${valueOf(row.name, definition.columns.name.type)},
        ${valueOf(row.managerId, definition.columns.managerId.type)}, ${valueOf(row.amount, definition.columns.amount.type)},
        ${valueOf(row.big, definition.columns.big.type)})`);
    }
    const base = db.from({ u: definition });
    const selected = base
      .orderBy(({ u }) => u.id.asc())
      .select(({ u }) => ({
        id: u.id,
        name: u.name,
        amount: u.amount,
        big: u.big,
      }));
    const rows = await selected.execute();
    assert.equal(rows.length, 3);
    assert.equal(rows[0]!.name, "O'Reilly 中😀");
    assert.equal(rows[0]!.amount, "123456789012345.1200");
    assert.equal(rows[0]!.big, 9007199254740993n);
    assert.equal(rows[1]!.big, -9007199254740993n);
    assert.equal(await selected.count(), 3n);
    assert.equal(await selected.limit(1, 1).count(), 1n);
    assert.equal(await selected.limit(0).first(), undefined);

    const joined = await base
      .leftJoin({ m: definition }, ({ u, m }) => u.managerId.eq(m.id))
      .orderBy(({ u }) => u.id.asc())
      .select(({ u, m }) => ({ id: u.id, manager: m.name }))
      .execute();
    assert.deepEqual(joined, [
      { id: 1, manager: null },
      { id: 2, manager: "O'Reilly 中😀" },
      { id: 3, manager: null },
    ]);

    const labelType = types.text().map({
      identity: Symbol("label"),
      to: (value: { text: string }) => value.text,
      from: (text) => ({ text }),
    });
    const labels = table(name, {
      id: column(types.integer()),
      label: column(labelType).named("name"),
    });
    const inner = db
      .from({ x: labels })
      .where(({ x }) => x.label.eq({ text: "O'Reilly 中😀" }))
      .select(({ x }) => ({ id: x.id, label: x.label }));
    const derived = await db
      .from({ d: inner.as() })
      .select(({ d }) => ({ label: d.label, id: d.id }))
      .first();
    assert.deepEqual(derived, { label: { text: "O'Reilly 中😀" }, id: 1 });

    const reordered = call(
      {
        name: "reverse-repeat",
        phase: "row",
        render: {
          [dialect.name]: (args: readonly SqlNode[]) =>
            sqlFragment`CONCAT(${args[1]!}, ${args[0]!}, ${args[1]!})`,
        },
      },
      types.text(),
      [valueOf("A", types.text()), valueOf("B", types.text())],
    );
    assert.equal((await base.select(() => ({ value: reordered })).first())!.value, "BAB");
    assert.deepEqual(
      await base
        .where(({ u }) => u.name.eq("x' OR 1=1 --"))
        .select(({ u }) => ({ id: u.id }))
        .execute(),
      [],
    );

    if (dialect.name === "mysql") {
      await db.transaction(async (tx) => {
        await tx.command(sql`SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES,STRICT_TRANS_TABLES'`);
        assert.deepEqual(
          await tx
            .from({ u: definition })
            .where(({ u }) => u.name.eq("x\\' OR 1=1 --"))
            .select(({ u }) => ({ id: u.id }))
            .execute(),
          [],
        );
      });
    }

    await assert.rejects(
      db.transaction(async (tx) => {
        await tx.command(sql`INSERT INTO ${identifier(name)} (id, name, manager_id, amount, big) VALUES (
        ${valueOf(4, types.integer())}, ${valueOf("rollback", types.text())}, NULL,
        ${valueOf("1.0000", types.decimal(19, 4))}, ${valueOf(1n, types.bigint())})`);
        assert.equal(
          await tx
            .from({ u: definition })
            .where(({ u }) => u.id.eq(4))
            .select(({ u }) => ({ id: u.id }))
            .count(),
          1n,
        );
        throw new Error("intentional rollback");
      }),
      /intentional rollback/,
    );
    assert.equal(
      await base
        .where(({ u }) => u.id.eq(4))
        .select(({ u }) => ({ id: u.id }))
        .count(),
      0n,
    );
  } finally {
    try {
      if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
    } finally {
      await harness.close();
    }
  }
}

test(
  "PostgreSQL typed query, codec, self join and transaction contract",
  { skip: process.env.EQTS_PG_TEST !== "1" },
  async () => {
    const pool = new PgPool({
      host: process.env.EQTS_PG_HOST ?? "127.0.0.1",
      port: Number(process.env.EQTS_PG_PORT ?? 55432),
      database: process.env.EQTS_PG_DATABASE ?? "eqp",
      user: process.env.EQTS_PG_USER ?? "eqp",
      password: required("EQTS_PG_PASSWORD"),
      max: 2,
      connectionTimeoutMillis: 10_000,
    });
    await verify({
      driver: postgresDriver(pool),
      dialect: postgresDialect,
      close: () => pool.end(),
    });
  },
);

test(
  "MySQL prepared query preserves exact results despite pool conversion options",
  { skip: process.env.EQTS_MYSQL_TEST !== "1" },
  async () => {
    const pool = createPool({
      host: process.env.EQTS_MYSQL_HOST ?? "127.0.0.1",
      port: Number(process.env.EQTS_MYSQL_PORT ?? 53306),
      database: process.env.EQTS_MYSQL_DATABASE ?? "eqp",
      user: process.env.EQTS_MYSQL_USER ?? "eqp",
      password: required("EQTS_MYSQL_PASSWORD"),
      connectionLimit: 2,
      connectTimeout: 10_000,
      charset: "utf8mb4",
      decimalNumbers: true,
      supportBigNumbers: false,
    });
    await verify({ driver: mysqlDriver(pool), dialect: mysqlDialect, close: () => pool.end() });
  },
);
