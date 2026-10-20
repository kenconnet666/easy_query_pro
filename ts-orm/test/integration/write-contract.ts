import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  column,
  createDatabase,
  identifier,
  sql,
  table,
  types,
  WriteExecutionError,
  type Dialect,
} from "../../src/index.js";

/** 每次用例拥有独立物理表；三库共用业务断言，仅 DDL/原生 RETURNING 的能力有所区别。 */
export async function verifyWrites(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
): Promise<void> {
  const name = "eqts_w_" + randomBytes(7).toString("hex");
  const label = types.text(100).map({
    identity: Symbol("write-label"),
    to: (value: { text: string }) => value.text,
    from: (text) => ({ text }),
  });
  const definition = table(name, {
    id: column(types.integer()).generated(),
    label: column(label).defaulted(),
    note: column(types.text(100)).nullable(),
    amount: column(types.decimal(19, 4)).defaulted(),
    big: column(types.bigint()).defaulted(),
    counter: column(types.integer()).defaulted(),
  });
  let created = false;
  try {
    const create = dialect.name === "hana" ? sql`CREATE COLUMN TABLE` : sql`CREATE TABLE`;
    const identity =
      dialect.name === "mysql"
        ? sql`INTEGER AUTO_INCREMENT`
        : sql`INTEGER GENERATED ALWAYS AS IDENTITY`;
    const text = dialect.name === "hana" ? sql`NVARCHAR(100)` : sql`VARCHAR(100)`;
    await db.command(sql`${create} ${identifier(name)} (
      ${identifier("id")} ${identity} PRIMARY KEY,
      ${identifier("label")} ${text} NOT NULL DEFAULT 'server default' UNIQUE,
      ${identifier("note")} ${text}, ${identifier("amount")} DECIMAL(19,4) NOT NULL DEFAULT 1.2300,
      ${identifier("big")} BIGINT NOT NULL DEFAULT 0, ${identifier("counter")} INTEGER NOT NULL DEFAULT 0)`);
    created = true;
    const result = await db
      .insert(definition)
      .values([
        {
          label: { text: "one" },
          note: "present",
          amount: "123456789012345.1200",
          big: 9007199254740993n,
        },
        { amount: "2.0000", big: -9007199254740993n },
        { label: { text: "three" }, note: null, amount: "3.0000", big: 3n },
      ])
      .execute();
    assert.equal(result.affectedRows, 3n);
    assert.deepEqual(result.rows, []);
    const base = db.from({ r: definition });
    const selected = base
      .orderBy(({ r }) => r.id.asc())
      .select(({ r }) => ({
        id: r.id,
        label: r.label,
        note: r.note,
        amount: r.amount,
        big: r.big,
        counter: r.counter,
      }));
    const rows = await selected.execute();
    assert.deepEqual(
      rows.map((row) => row.label),
      [{ text: "one" }, { text: "server default" }, { text: "three" }],
    );
    assert.deepEqual(
      rows.map((row) => row.note),
      ["present", null, null],
    );
    assert.equal(rows[0]!.amount, "123456789012345.1200");
    assert.equal(rows[0]!.big, 9007199254740993n);
    assert.equal(rows[1]!.big, -9007199254740993n);
    assert.deepEqual(
      rows.map((row) => row.counter),
      [0, 0, 0],
    );
    assert.equal(
      (
        await db
          .update(definition)
          .set((row) => ({ counter: row.counter.add(1) }))
          .where((row) => row.label.eq({ text: "one" }))
          .execute()
      ).affectedRows,
      1n,
    );
    assert.equal(
      (await base
        .where(({ r }) => r.label.eq({ text: "one" }))
        .select(({ r }) => ({ value: r.counter }))
        .first())!.value,
      1,
    );
    assert.equal(
      (
        await db
          .deleteFrom(definition)
          .where((row) => row.counter.eq(1))
          .execute()
      ).affectedRows,
      1n,
    );

    // HANA 分成两条 INSERT，PG/MySQL 可是一条多行 VALUES；事务契约不依赖批处理形状。
    await assert.rejects(
      db.transaction(async (tx) => {
        await tx
          .insert(definition)
          .values([{ label: { text: "duplicate" } }, { label: { text: "duplicate" } }])
          .execute();
      }),
      WriteExecutionError,
    );
    assert.equal(
      await base
        .where(({ r }) => r.label.eq({ text: "duplicate" }))
        .select(({ r }) => ({ id: r.id }))
        .count(),
      0n,
    );

    if (dialect.name === "postgres") {
      const inserted = await db
        .insert(definition)
        .values({
          label: { text: "returned" },
          amount: "123456789012345.1200",
          big: 9007199254740993n,
        })
        .returning((row) => ({ amount: row.amount, big: row.big, label: row.label, id: row.id }))
        .execute();
      assert.equal(inserted.affectedRows, 1n);
      assert.equal(inserted.rows[0]!.amount, "123456789012345.1200");
      assert.equal(inserted.rows[0]!.big, 9007199254740993n);
      assert.deepEqual(inserted.rows[0]!.label, { text: "returned" });
      const updated = await db
        .update(definition)
        .set((row) => ({ counter: row.counter.add(2) }))
        .where((row) => row.id.eq(inserted.rows[0]!.id))
        .returning((row) => ({ counter: row.counter, label: row.label }))
        .execute();
      assert.deepEqual(updated.rows, [{ counter: 2, label: { text: "returned" } }]);
      const removed = await db
        .deleteFrom(definition)
        .where((row) => row.id.eq(inserted.rows[0]!.id))
        .returning((row) => ({ label: row.label }))
        .execute();
      assert.deepEqual(removed.rows, [{ label: { text: "returned" } }]);

      const failingLabel = types.text(100).map({
        identity: Symbol("failing-label"),
        to: (value: { text: string }) => value.text,
        from: (_value: string): { text: string } => {
          throw new Error("intentional decoder failure");
        },
      });
      const badReader = table(name, {
        ...definition.columns,
        label: column(failingLabel).defaulted(),
      });
      await assert.rejects(
        db.transaction(async (tx) => {
          await tx
            .insert(badReader)
            .values({ label: { text: "bad decode" } })
            .returning((row) => ({ label: row.label }))
            .execute();
        }),
        (error) => {
          assert.ok(error instanceof WriteExecutionError);
          assert.equal(error.completedStatements, 1);
          assert.match(String(error.cause), /Cannot decode projection/);
          return true;
        },
      );
      assert.equal(
        await base
          .where(({ r }) => r.label.eq({ text: "bad decode" }))
          .select(({ r }) => ({ id: r.id }))
          .count(),
        0n,
      );
    } else {
      assert.throws(
        () =>
          db
            .insert(definition)
            .values({ label: { text: "unsupported returning" } })
            .returning((row) => ({ id: row.id }))
            .compile(),
        /RETURNING/,
      );
    }
    assert.equal(
      (
        await db
          .insert(definition)
          .values([{ label: { text: "batch one" } }, { label: { text: "batch two" } }])
          .execute()
      ).affectedRows,
      2n,
    );
    await db.deleteFrom(definition).allRows().execute();
    if (dialect.name === "hana") {
      // 只检查编译拒绝，绝不向 HANA 发送已知存在缺陷的零列/identity override 形式。
      assert.throws(() => db.insert(definition).values({}).compile(), /explicit column/);
    } else {
      assert.equal((await db.insert(definition).values({}).execute()).affectedRows, 1n);
      const defaults = await selected.first();
      assert.deepEqual(defaults!.label, { text: "server default" });
      assert.equal(defaults!.amount, "1.2300");
      assert.equal(defaults!.big, 0n);
    }
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
