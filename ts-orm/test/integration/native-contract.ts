import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  column,
  coalesce,
  createDatabase,
  DataType,
  identifier,
  nativeCast,
  postgresTypes,
  sql,
  table,
  types,
  valueOf,
  windowFunctions,
  windowSpec,
  type Dialect,
} from "../../src/index.js";

export async function verifyNativeTypes(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
): Promise<void> {
  if (dialect.name === "postgres") await verifyUuid(db);
  if (dialect.name === "mysql") await verifyNullableCast(db);
}

async function verifyUuid(db: ReturnType<typeof createDatabase>): Promise<void> {
  const name = "eqts_uuid_" + randomBytes(7).toString("hex");
  const idType = postgresTypes.uuid().map({
    identity: Symbol("document-id"),
    to: (value: { value: string }) => value.value,
    from: (value) => ({ value }),
  });
  const definition = table(name, { id: column(idType), parent: column(idType).nullable() });
  const first = { value: "00000000-0000-0000-0000-000000000001" };
  const second = { value: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA" };
  const secondRead = { value: second.value.toLowerCase() };
  let created = false;
  try {
    await db.command(
      sql`CREATE TABLE ${identifier(name)} (${identifier("id")} UUID PRIMARY KEY, ${identifier("parent")} UUID)`,
    );
    created = true;
    const inserted = await db
      .insert(definition)
      .values([{ id: first }, { id: second, parent: first }])
      .returning((row) => ({ id: row.id, parent: row.parent }))
      .execute();
    assert.deepEqual(inserted.rows, [
      { id: first, parent: null },
      { id: secondRead, parent: first },
    ]);
    const base = db.from({ d: definition });
    assert.deepEqual(
      await base
        .where(({ d }) => d.id.eq(second))
        .select(({ d }) => ({ id: d.id }))
        .first(),
      { id: secondRead },
    );
    const joined = base
      .leftJoin({ p: definition }, ({ d, p }) => d.parent.eq(p.id))
      .select(({ d, p }) => ({ id: d.id, parent: p.id }))
      .asCte("uuid_joined");
    const selected = db
      .from({ j: joined })
      .orderBy(({ j }) => j.id.asc())
      .select(({ j }) => ({ id: j.id, parent: j.parent }));
    assert.deepEqual((await selected.page(1, 10)).rows, [
      { id: first, parent: null },
      { id: secondRead, parent: first },
    ]);
    assert.equal(
      await db
        .from({ u: selected.union(selected) })
        .select(({ u }) => ({ id: u.id }))
        .count(),
      2n,
    );
    const order = windowSpec().orderBy(base.scope.d.id.asc());
    const windowed = base
      .select(({ d }) => ({ id: d.id, previous: windowFunctions.lag(d.id).over(order) }))
      .asCte("uuid_window");
    assert.deepEqual(
      await db
        .from({ w: windowed })
        .orderBy(({ w }) => w.id.asc())
        .select(({ w }) => ({ id: w.id, previous: w.previous }))
        .execute(),
      [
        { id: first, previous: null },
        { id: secondRead, previous: first },
      ],
    );
    assert.deepEqual(
      await base
        .where(({ d }) => d.id.eq(first))
        .select(({ d }) => ({
          fallback: coalesce(d.parent, valueOf(second, idType)),
          converted: nativeCast(valueOf(second.value, types.text()), postgresTypes.uuid()),
        }))
        .first(),
      { fallback: secondRead, converted: secondRead.value },
    );
    assert.equal(
      (
        await db
          .update(definition)
          .set({ parent: null })
          .where((row) => row.id.eq(second))
          .execute()
      ).affectedRows,
      1n,
    );
    assert.deepEqual(
      await base
        .orderBy(({ d }) => d.id.asc())
        .select(({ d }) => ({ parent: d.parent }))
        .execute(),
      [{ parent: null }, { parent: null }],
    );
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}

async function verifyNullableCast(db: ReturnType<typeof createDatabase>): Promise<void> {
  // 不提前实现共同 DATE；用应用原生 descriptor 证明 CAST 的 SQL NULL 是数据库转换结果。
  const date = new DataType<string, "mysql:date-test">(
    {
      kind: "mysql:date-test",
      native: {
        identity: Symbol("date-test"),
        dialects: { mysql: { parameter: ["CAST(", " AS DATE)"], cast: ["CAST(", " AS DATE)"] } },
      },
    },
    types.text().codec,
  );
  await db.transaction(async (tx) => {
    const original = (await tx.command(sql`SELECT @@sql_mode AS mode`)).rows[0]?.mode;
    assert.equal(typeof original, "string");
    try {
      await tx.command(sql`SET SESSION sql_mode = 'NO_ZERO_DATE'`);
      const zero = await tx.command(
        sql`SELECT ${nativeCast(valueOf("0000-00-00", types.text()), date)} AS bound, CAST('0000-00-00' AS DATE) AS literal`,
      );
      // MySQL 8.4 对参数与字面量零日期的结果不同，不能把文档中的常量折叠行为冒充参数保证。
      assert.equal(zero.rows[0]?.bound, "0000-00-00");
      assert.equal(zero.rows[0]?.literal, null);
      const result = await tx.command(
        sql`SELECT ${nativeCast(valueOf("not-a-date", types.text()), date)} AS converted`,
      );
      assert.equal(result.rows[0]?.converted, null);
      assert.equal(
        nativeCast(valueOf("not-a-date", types.text()), date).type.decode(
          result.rows[0]?.converted,
        ),
        null,
      );
    } finally {
      await tx.command(
        sql`SET SESSION sql_mode = ${valueOf(original as string, types.text(5000))}`,
      );
    }
  });
}
