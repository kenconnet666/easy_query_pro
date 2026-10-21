import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  aggregate,
  column,
  createDatabase,
  identifier,
  jsonNull,
  sql,
  table,
  types,
  windowSpec,
  type Dialect,
} from "../../src/index.js";

export async function verifyJson(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
): Promise<void> {
  const name = "eqts_json_" + randomBytes(7).toString("hex");
  const document = types.json((value) => value);
  const raw = types.jsonText();
  const definition = table(name, {
    id: column(types.integer()),
    document: column(document).nullable(),
    raw: column(raw).nullable(),
  });
  const storage =
    dialect.name === "postgres" ? sql`JSONB` : dialect.name === "mysql" ? sql`JSON` : sql`NCLOB`;
  let created = false;
  try {
    await db.command(
      sql`CREATE TABLE ${identifier(name)} (${identifier("id")} INTEGER PRIMARY KEY, ${identifier("document")} ${storage}, ${identifier("raw")} ${storage})`,
    );
    created = true;
    const nested = { text: "中文 😀 O'Reilly \\", values: [null, "null", 0.125], empty: {} };
    await db
      .insert(definition)
      .values([
        { id: 1, document: jsonNull, raw: "null" },
        { id: 2, document: null, raw: null },
        { id: 3, document: nested, raw: '{"n":9007199254740993}' },
        { id: 4, document: "null", raw: '"null"' },
      ])
      .execute();
    const base = db.from({ j: definition });
    const rows = await base
      .orderBy(({ j }) => j.id.asc())
      .select(({ j }) => ({ document: j.document, raw: j.raw, missing: j.document.isNull() }))
      .execute();
    assert.deepEqual(
      rows.map((row) => row.document),
      [jsonNull, null, nested, "null"],
    );
    assert.deepEqual(
      rows.map((row) => row.missing),
      [false, true, false, false],
    );
    assert.equal(rows[0]!.raw, "null");
    assert.equal(rows[1]!.raw, null);
    assert.match(rows[2]!.raw!, /9007199254740993/);
    assert.equal(rows[3]!.raw, '"null"');
    await assert.doesNotReject(async () => {
      const counted = await base
        .select(({ j }) => ({
          count: aggregate.count(j.document),
          filtered: aggregate.count(j.document).filter(j.id.lt(3)),
        }))
        .first();
      assert.deepEqual(counted, { count: 3n, filtered: 1n });
    }, "JSON COUNT and filtered COUNT must preserve SQL NULL semantics");
    await assert.doesNotReject(async () => {
      const windowCounts = await base
        .select(({ j }) => ({
          count: aggregate.count(j.document).over(windowSpec()),
          filtered: aggregate.count(j.document).filter(j.id.lt(3)).over(windowSpec()),
        }))
        .execute();
      assert.deepEqual(
        windowCounts,
        Array.from({ length: 4 }, () => ({ count: 3n, filtered: 1n })),
      );
    }, "JSON window COUNT must preserve SQL NULL semantics");
    if (dialect.name !== "hana") {
      const comparisons = await base
        .orderBy(({ j }) => j.id.asc())
        .select(({ j }) => ({
          jsonNull: j.document.eqNullSafe(jsonNull),
          sqlNull: j.document.eqNullSafe(null),
          different: j.document.neNullSafe(jsonNull),
          raw: j.raw.eqNullSafe("null"),
        }))
        .execute();
      assert.deepEqual(comparisons, [
        { jsonNull: true, sqlNull: false, different: false, raw: true },
        { jsonNull: false, sqlNull: true, different: true, raw: false },
        { jsonNull: false, sqlNull: false, different: true, raw: false },
        { jsonNull: false, sqlNull: false, different: true, raw: false },
      ]);
      const groups = await base
        .groupBy(({ j }) => ({ document: j.document }))
        .select((_, __, keys) => ({ document: keys.document, count: aggregate.count() }))
        .execute();
      assert.equal(groups.length, 4);
      assert.ok(groups.every((row) => row.count === 1n));
      assert.equal(groups.filter((row) => row.document === null).length, 1);
      assert.equal(groups.filter((row) => row.document === jsonNull).length, 1);
      assert.equal(
        (await base.select(({ j }) => ({ count: aggregate.count(j.document).distinct() })).first())!
          .count,
        3n,
      );
    }
    const scalar = db
      .from({ source: definition })
      .where(({ source }) => source.id.eq(1))
      .select(({ source }) => ({ value: source.document }))
      .scalar();
    assert.equal(
      (
        await base
          .where(({ j }) => j.id.eq(1))
          .select(() => ({ value: scalar }))
          .first()
      )?.value,
      jsonNull,
    );

    const cte = base
      .select(({ j }) => ({ id: j.id, document: j.document, raw: j.raw }))
      .asCte("json_documents");
    const report = db
      .from({ d: cte })
      .orderBy(({ d }) => d.id.asc())
      .select(({ d }) => ({ id: d.id, document: d.document }));
    const page = await report.page(1, 2);
    assert.equal(page.total, 4n);
    assert.deepEqual(page.rows, [
      { id: 1, document: jsonNull },
      { id: 2, document: null },
    ]);
    const joined = await base
      .leftJoin({ absent: definition }, ({ absent }) => absent.id.eq(999))
      .where(({ j }) => j.id.eq(1))
      .select(({ j, absent }) => ({ present: j.document, missing: absent.document }))
      .first();
    assert.deepEqual(joined, { present: jsonNull, missing: null });

    // 原始文本只保证 ORM 不再舍入；此整数可由三库存储，读取为 JS 文档必须拒绝不安全 number。
    const parsedRaw = table(name, {
      id: column(types.integer()),
      raw: column(document).nullable(),
    });
    await assert.rejects(
      db
        .from({ j: parsedRaw })
        .where(({ j }) => j.id.eq(3))
        .select(({ j }) => ({ value: j.raw }))
        .execute(),
      (error) =>
        error instanceof TypeError &&
        error.cause instanceof TypeError &&
        /JSON number/.test(error.cause.message),
    );
    await db
      .update(definition)
      .set({ document: jsonNull })
      .where((j) => j.id.eq(4))
      .execute();
    assert.equal(
      (
        await base
          .where(({ j }) => j.id.eq(4))
          .select(({ j }) => ({ value: j.document }))
          .first()
      )?.value,
      jsonNull,
    );
    await db
      .update(definition)
      .set({ document: null })
      .where((j) => j.id.eq(4))
      .execute();
    assert.equal(
      await base
        .where(({ j }) => j.document.isNull())
        .select(({ j }) => ({ id: j.id }))
        .count(),
      2n,
    );
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
