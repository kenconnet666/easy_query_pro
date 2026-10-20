import test from "node:test";
import assert from "node:assert/strict";
import type { Pool as MySqlPool } from "mysql2/promise";
import type { ConnectionPool } from "@sap/hana-client";
import { createDatabase, hanaDialect, mysqlDialect } from "../../src/index.js";
import { mysqlDriver } from "../../src/drivers/mysql.js";
import { hanaDriver } from "../../src/drivers/hana.js";

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
