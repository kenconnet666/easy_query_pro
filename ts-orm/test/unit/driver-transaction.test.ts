import test from "node:test";
import assert from "node:assert/strict";
import type { Pool as MySqlPool } from "mysql2/promise";
import type { ConnectionPool } from "@sap/hana-client";
import { createDatabase, hanaDialect, mysqlDialect } from "../../src/index.js";
import { mysqlDriver } from "../../src/drivers/mysql.js";
import { hanaDriver } from "../../src/drivers/hana.js";
import { postgresDriver } from "../../src/drivers/postgres.js";
import type { Pool as PgPool } from "pg";

test("MySQL destroys a lease when next-transaction setup succeeds but START fails", async () => {
  for (const failingStep of [
    "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ",
    "START TRANSACTION READ ONLY",
  ]) {
    const statements: string[] = [];
    let destroyed = false;
    const original = new Error("control failure");
    // 只模拟驱动协议边界，证明配置失败后的租约不会回到可复用队列。
    const pool = {
      async getConnection() {
        return {
          async query(sql: string) {
            statements.push(sql);
            if (sql === failingStep) throw original;
            return [];
          },
          destroy() {
            destroyed = true;
          },
          release() {
            assert.fail("failed setup cannot release a reusable connection");
          },
        };
      },
    } as unknown as MySqlPool;
    const db = createDatabase({ dialect: mysqlDialect, driver: mysqlDriver(pool) });
    await assert.rejects(
      db.transaction(async () => assert.fail("callback must not run"), {
        isolationLevel: "repeatableRead",
        readOnly: true,
      }),
      (error) => error === original,
    );
    assert.ok(destroyed);
    assert.equal(statements.at(-1), "ROLLBACK AND NO CHAIN NO RELEASE");
  }
});

test("the HANA pooled adapter rejects persistent access-mode options before borrowing", async () => {
  let acquired = false;
  const pool = {
    getConnection() {
      acquired = true;
      assert.fail("must not borrow");
    },
  } as unknown as ConnectionPool;
  const db = createDatabase({ dialect: hanaDialect, driver: hanaDriver(pool) });
  for (const readOnly of [true, false])
    await assert.rejects(
      db.transaction(async () => assert.fail("callback must not run"), { readOnly }),
      /readOnly/,
    );
  assert.equal(acquired, false);
});

test("PostgreSQL NULL-array checks and writes cannot be interleaved by a queued SET", async () => {
  const events: string[] = [];
  let mode = "on";
  let entered!: () => void;
  let finish!: () => void;
  const checking = new Promise<void>((resolve) => {
    entered = resolve;
  });
  const pending = new Promise<void>((resolve) => {
    finish = resolve;
  });
  let first = true;
  const pool = {
    async connect() {
      return {
        async query(config: string | { text: string }) {
          const text = typeof config === "string" ? config : config.text;
          events.push(text);
          if (text === "SHOW array_nulls") {
            if (first) {
              first = false;
              entered();
              await pending;
            }
            return { rows: [{ array_nulls: mode }], rowCount: 1, command: "SHOW" };
          }
          if (text === "SET array_nulls = off") mode = "off";
          return { rows: [], rowCount: 1, command: "INSERT" };
        },
        release() {
          events.push("release");
        },
      };
    },
  } as unknown as PgPool;
  const connection = await postgresDriver(pool).acquire();
  try {
    const insert = connection.execute("INSERT NULL ARRAY", [[null]]);
    await checking;
    const change = connection.execute("SET array_nulls = off", []);
    await Promise.resolve();
    assert.deepEqual(events, ["SHOW array_nulls"]);
    finish();
    await Promise.all([insert, change]);
    assert.deepEqual(events, ["SHOW array_nulls", "INSERT NULL ARRAY", "SET array_nulls = off"]);
    for (const value of [[null], [undefined], new Array(1)])
      await assert.rejects(connection.execute("MUST NOT WRITE", [value]), /array_nulls=on/);
    assert.ok(!events.includes("MUST NOT WRITE"));
    await connection.execute("QUOTED NULL TEXT", [["NULL"]]);
    assert.equal(events.at(-1), "QUOTED NULL TEXT");
  } finally {
    finish();
    await connection.release();
  }
});
