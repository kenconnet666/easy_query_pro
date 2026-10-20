import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  column,
  createDatabase,
  identifier,
  sql,
  table,
  types,
  type Database,
  type Dialect,
  type Driver,
  type IsolationLevel,
} from "../../src/index.js";

/** 由两个独立租约制造真实提交；只比较 RC/RR，SERIALIZABLE 的范围锁不能被误判成测试卡死。 */
export async function verifyTransactions(
  db: Database,
  driver: Driver,
  observer: Driver,
  dialect: Dialect,
): Promise<void> {
  const name = "eqts_tx_" + randomBytes(7).toString("hex");
  const definition = table(name, { id: column(types.integer()) });
  const outside = createDatabase({ dialect, driver: observer });
  const rows = (client: Database) =>
    client
      .from({ r: definition })
      .orderBy(({ r }) => r.id.asc())
      .select(({ r }) => ({ id: r.id }));
  let created = false;
  try {
    const create = dialect.name === "hana" ? sql`CREATE COLUMN TABLE` : sql`CREATE TABLE`;
    await db.command(sql`${create} ${identifier(name)} (${identifier("id")} INTEGER PRIMARY KEY)`);
    created = true;
    const reset = async () => {
      await db.deleteFrom(definition).allRows().execute();
      await db.insert(definition).values({ id: 1 }).execute();
    };
    for (const isolationLevel of ["readCommitted", "repeatableRead"] as const) {
      await reset();
      let interleaved = false;
      const interleaving: Driver = {
        transactionCapabilities: driver.transactionCapabilities!,
        async acquire() {
          const connection = await driver.acquire();
          return {
            async execute(sql, parameters) {
              const result = await connection.execute(sql, parameters);
              if (!interleaved && sql.startsWith("SELECT COUNT(*)")) {
                interleaved = true;
                await outside.insert(definition).values({ id: 2 }).execute();
              }
              return result;
            },
            begin: (options) => connection.begin(options),
            commit: () => connection.commit(),
            rollback: () => connection.rollback(),
            release: () => connection.release(),
          };
        },
      };
      const selected = createDatabase({ dialect, driver: interleaving });
      const page = await selected.transaction((tx) => rows(tx).page(1, 10), { isolationLevel });
      assert.ok(interleaved);
      assert.equal(page.total, 1n);
      assert.deepEqual(
        page.rows,
        isolationLevel === "readCommitted" ? [{ id: 1 }, { id: 2 }] : [{ id: 1 }],
      );
      assert.equal(await rows(db).count(), 2n);
    }
    await db.transaction(
      async (tx) => {
        assert.equal((await rows(tx).page(2, 1)).rows[0]?.id, 2);
      },
      { isolationLevel: "serializable" },
    );

    if (dialect.name === "hana") {
      // SDK 显式池会复原隔离级别；主 HANA 测试池容量为 1，检查确实复用同一个会话。
      const current = async (client: Database) =>
        (
          await client.command(
            sql`SELECT CURRENT_TRANSACTION_ISOLATION_LEVEL AS "level" FROM DUMMY`,
          )
        ).rows[0]?.level;
      const baseline = await current(db);
      const expected: Record<IsolationLevel, string> = {
        readCommitted: "READ COMMITTED",
        repeatableRead: "REPEATABLE READ",
        serializable: "SERIALIZABLE",
      };
      for (const isolationLevel of Object.keys(expected) as IsolationLevel[]) {
        await db.transaction(
          async (tx) => assert.equal(await current(tx), expected[isolationLevel]),
          { isolationLevel },
        );
        assert.equal(await current(db), baseline);
      }
      for (const readOnly of [true, false])
        await assert.rejects(
          db.transaction(async () => assert.fail("unsupported option must fail before callback"), {
            readOnly,
          }),
          /does not support.*readOnly/,
        );
    } else {
      await db.transaction(
        async (tx) => {
          assert.equal((await rows(tx).page(1, 10)).total, 2n);
        },
        { isolationLevel: "repeatableRead", readOnly: true },
      );
      await assert.rejects(
        db.transaction(
          async (tx) => {
            await tx.insert(definition).values({ id: 3 }).execute();
          },
          { readOnly: true },
        ),
      );
      // 前一个失败只读事务不能污染随后普通借用的访问模式。
      await db.insert(definition).values({ id: 3 }).execute();
      assert.equal(await rows(db).count(), 3n);
    }
    if (dialect.name === "postgres") {
      await assert.rejects(
        db.transaction(async (tx) => {
          await tx.insert(definition).values({ id: 4 }).execute();
          // 捕获业务 SQL 错误并不把 PostgreSQL 的 aborted transaction 恢复为可提交状态。
          await assert.rejects(tx.command(sql`SELECT 1 / 0`));
          return "callback returned successfully";
        }),
        /rolled back instead of committed/,
      );
      assert.equal(
        await rows(db)
          .where(({ r }) => r.id.eq(4))
          .count(),
        0n,
      );
    }
    if (dialect.name === "mysql") {
      // 固定这一条独占租约，以便在 ORM 归还后检查原会话，而非碰巧借到另一个连接。
      const connection = await driver.acquire();
      const pinned = createDatabase({
        dialect,
        driver: {
          transactionCapabilities: driver.transactionCapabilities!,
          async acquire() {
            return {
              execute: (s, p) => connection.execute(s, p),
              begin: (o) => connection.begin(o),
              commit: () => connection.commit(),
              rollback: () => connection.rollback(),
              async release() {},
            };
          },
        },
      });
      try {
        const initial = await connection.execute("SELECT @@completion_type AS mode", []);
        const initialMode = new Map([
          ["NO_CHAIN", 0],
          ["CHAIN", 1],
          ["RELEASE", 2],
        ]).get(String(initial.rows[0]?.mode));
        assert.notEqual(initialMode, undefined);
        let nextId = 10;
        const verifyAutoCommit = async () => {
          const id = nextId++;
          await pinned.insert(definition).values({ id }).execute();
          assert.equal(
            await rows(outside)
              .where(({ r }) => r.id.eq(id))
              .count(),
            1n,
          );
        };
        try {
          for (const mode of [1, 2]) {
            await connection.execute("SET SESSION completion_type = " + mode, []);
            await pinned.transaction(async (tx) => {
              await rows(tx).count();
            });
            await verifyAutoCommit();
            await assert.rejects(
              pinned.transaction(async () => {
                throw new Error("intentional rollback");
              }),
              /intentional rollback/,
            );
            await verifyAutoCommit();
          }
        } finally {
          await connection.execute("SET SESSION completion_type = " + initialMode, []);
        }
      } finally {
        await connection.release();
      }
    }
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
