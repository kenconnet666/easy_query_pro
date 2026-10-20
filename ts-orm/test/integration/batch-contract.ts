import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  column,
  createDatabase,
  dialect as createDialect,
  identifier,
  sql,
  table,
  types,
  valueOf,
  type Dialect,
  type Driver,
} from "../../src/index.js";

/** 用小预算验证真实的多语句写入，不需要为协议上限构造万列数据库表。 */
export async function verifyBatchBudget(driver: Driver, dialect: Dialect): Promise<void> {
  const db = createDatabase({ dialect: createDialect(dialect.name, { maxParameters: 4 }), driver });
  const name = "eqts_batch_" + randomBytes(7).toString("hex");
  const definition = table(name, { id: column(types.integer()), label: column(types.text(50)) });
  const rows = Array.from({ length: 5 }, (_, i) => ({ id: i + 1, label: `批次-${i + 1}` }));
  let created = false;
  try {
    const create = dialect.name === "hana" ? sql`CREATE COLUMN TABLE` : sql`CREATE TABLE`;
    const text = dialect.name === "hana" ? sql`NVARCHAR(50)` : sql`VARCHAR(50)`;
    await db.command(
      sql`${create} ${identifier(name)} (${identifier("id")} INTEGER PRIMARY KEY, ${identifier("label")} ${text})`,
    );
    created = true;
    await db.transaction(async (tx) => {
      const insert = tx.insert(definition).values(rows);
      if (dialect.name === "postgres") {
        const returning = insert.returning((row) => ({
          id: row.id,
          marker: valueOf("fixed", types.text()),
        }));
        assert.equal(returning.compile().length, 5);
        const result = await returning.execute();
        assert.equal(result.affectedRows, 5n);
        assert.deepEqual(
          result.rows,
          rows.map((row) => ({ id: row.id, marker: "fixed" })),
        );
      } else {
        assert.equal(insert.compile().length, dialect.name === "hana" ? 5 : 3);
        assert.equal((await insert.execute()).affectedRows, 5n);
      }
    });
    assert.deepEqual(
      await db
        .from({ r: definition })
        .orderBy(({ r }) => r.id.asc())
        .select(({ r }) => ({ id: r.id, label: r.label }))
        .execute(),
      rows,
    );
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
