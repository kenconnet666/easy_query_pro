import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  aggregate,
  column,
  createDatabase,
  frame,
  identifier,
  sql,
  table,
  types,
  windowFunctions,
  windowSpec,
  type Dialect,
} from "../../src/index.js";

export async function verifyWindows(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
): Promise<void> {
  const name = "eqts_win_" + randomBytes(7).toString("hex");
  const label = types.text(20).map({
    identity: Symbol("window-label"),
    to: (value: { text: string }) => value.text,
    from: (text) => ({ text }),
  });
  const events = table(name, {
    id: column(types.integer()),
    bucket: column(types.integer()),
    value: column(types.integer()).nullable(),
    weight: column(types.integer()),
    label: column(label).nullable(),
  });
  let created = false;
  try {
    const create = dialect.name === "hana" ? sql`CREATE COLUMN TABLE` : sql`CREATE TABLE`;
    const text = dialect.name === "hana" ? sql`NVARCHAR(20)` : sql`VARCHAR(20)`;
    await db.command(sql`${create} ${identifier(name)} (${identifier("id")} INTEGER PRIMARY KEY,${identifier("bucket")} INTEGER,
      ${identifier("value")} INTEGER,${identifier("weight")} INTEGER,${identifier("label")} ${text})`);
    created = true;
    await db
      .insert(events)
      .values([
        { id: 1, bucket: 1, value: 10, weight: 10, label: { text: "one" } },
        { id: 2, bucket: 1, value: null, weight: 20, label: null },
        { id: 3, bucket: 2, value: 30, weight: 30, label: { text: "three" } },
      ])
      .execute();
    const base = db.from({ e: events });
    // PG/MySQL 的反向同类边界表示空帧，HANA88 拒绝；共同 API 因此选择非反向范围。
    const reverseQuery = sql`SELECT COUNT(*) OVER (ORDER BY ${identifier("id")} ROWS BETWEEN 1 PRECEDING AND 2 PRECEDING) AS ${identifier("n")}, SUM(${identifier("weight")}) OVER (ORDER BY ${identifier("id")} ROWS BETWEEN 1 PRECEDING AND 2 PRECEDING) AS ${identifier("s")} FROM ${identifier(name)}`;
    if (dialect.name === "hana")
      await assert.rejects(db.command(reverseQuery), /window frame|start point must precede/i);
    else {
      const reverse = await db.command(reverseQuery);
      assert.deepEqual(
        reverse.rows.map((row) => BigInt(row["n"] as string | number)),
        [0n, 0n, 0n],
      );
      assert.deepEqual(
        reverse.rows.map((row) => row["s"]),
        [null, null, null],
      );
    }
    const ordered = windowSpec().orderBy(base.scope.e.id.asc());
    const nextRow = ordered.rowsBetween(frame.following(1), frame.following(1));
    const allRows = ordered.rowsBetween(frame.unboundedPreceding, frame.unboundedFollowing);
    const rows = await base
      .orderBy(({ e }) => e.id.asc())
      .select(({ e }) => ({
        id: e.id,
        lag: windowFunctions.lag(e.value, 1, 999).over(ordered),
        lead: windowFunctions.lead(e.value, 1, 999).over(ordered),
        current: windowFunctions.lag(e.value, 0, 999).over(ordered),
        nextAll: aggregate.count().over(nextRow),
        nextPresent: aggregate.count(e.value).over(nextRow),
        nextSum: aggregate.sum(e.value).over(nextRow),
        nextMin: aggregate.min(e.value).over(nextRow),
        nextMax: aggregate.max(e.value).over(nextRow),
        nextFirst: windowFunctions.firstValue(e.value).over(nextRow),
        nextLast: windowFunctions.lastValue(e.value).over(nextRow),
        nextNth: windowFunctions.nthValue(e.value, 1).over(nextRow),
        second: windowFunctions.nthValue(e.value, 2).over(nextRow),
        lastDefault: windowFunctions.lastValue(e.value).over(ordered),
        lastFull: windowFunctions.lastValue(e.value).over(allRows),
        filtered: aggregate.count().filter(e.value.gt(15)).over(nextRow),
        label: windowFunctions.lag(e.label, 1, { text: "missing" }).over(ordered),
      }))
      .execute();
    assert.deepEqual(
      rows.map((row) => row.lag),
      [999, 10, null],
    );
    assert.deepEqual(
      rows.map((row) => row.lead),
      [null, 30, 999],
    );
    assert.deepEqual(
      rows.map((row) => row.current),
      [10, null, 30],
    );
    assert.deepEqual(
      rows.map((row) => row.nextAll),
      [1n, 1n, 0n],
    );
    assert.deepEqual(
      rows.map((row) => row.nextPresent),
      [0n, 1n, 0n],
    );
    assert.deepEqual(
      rows.map((row) => row.nextSum),
      [null, "30", null],
    );
    for (const key of ["nextMin", "nextMax", "nextFirst", "nextLast", "nextNth"] as const)
      assert.deepEqual(
        rows.map((row) => row[key]),
        [null, 30, null],
      );
    assert.deepEqual(
      rows.map((row) => row.second),
      [null, null, null],
    );
    assert.deepEqual(
      rows.map((row) => row.lastDefault),
      [10, null, 30],
    );
    assert.deepEqual(
      rows.map((row) => row.lastFull),
      [30, 30, 30],
    );
    assert.deepEqual(
      rows.map((row) => row.filtered),
      [0n, 1n, 0n],
    );
    assert.deepEqual(
      rows.map((row) => row.label),
      [{ text: "missing" }, { text: "one" }, null],
    );

    const peers = windowSpec().orderBy(base.scope.e.bucket.asc());
    const stable = peers.orderBy(base.scope.e.id.asc());
    const cumulative = stable.rowsBetween(frame.unboundedPreceding, frame.currentRow);
    const ranked = await base
      .orderBy(({ e }) => e.id.asc())
      .select(({ e }) => ({
        row: windowFunctions.rowNumber().over(stable),
        rank: windowFunctions.rank().over(peers),
        dense: windowFunctions.denseRank().over(peers),
        peerSum: aggregate.sum(e.weight).over(peers),
        rowsSum: aggregate.sum(e.weight).over(cumulative),
        total: aggregate.sum(e.weight).over(windowSpec()),
        partition: aggregate.count().over(windowSpec().partitionBy(e.bucket)),
      }))
      .execute();
    assert.deepEqual(
      ranked.map((row) => row.row),
      [1n, 2n, 3n],
    );
    assert.deepEqual(
      ranked.map((row) => row.rank),
      [1n, 1n, 3n],
    );
    assert.deepEqual(
      ranked.map((row) => row.dense),
      [1n, 1n, 2n],
    );
    assert.deepEqual(
      ranked.map((row) => row.peerSum),
      ["30", "30", "60"],
    );
    assert.deepEqual(
      ranked.map((row) => row.rowsSum),
      ["10", "30", "60"],
    );
    assert.deepEqual(
      ranked.map((row) => row.total),
      ["60", "60", "60"],
    );
    assert.deepEqual(
      ranked.map((row) => row.partition),
      [2n, 2n, 1n],
    );
    assert.deepEqual(
      new Set(
        (
          await base
            .select(() => ({ row: windowFunctions.rowNumber().over(windowSpec()) }))
            .execute()
        ).map((row) => row.row),
      ),
      new Set([1n, 2n, 3n]),
    );
    assert.equal(
      await base
        .select(() => ({ rank: windowFunctions.denseRank().over(peers) }))
        .distinct()
        .count(),
      2n,
    );

    for (const direction of ["asc", "desc"] as const)
      for (const placement of ["first", "last"] as const) {
        const sort = base.scope.e.value[direction](placement);
        const expected =
          direction === "asc"
            ? placement === "first"
              ? [2, 1, 3]
              : [1, 3, 2]
            : placement === "first"
              ? [2, 3, 1]
              : [3, 1, 2];
        const direct = await base
          .orderBy(() => [sort, base.scope.e.id.asc()])
          .select(({ e }) => ({ id: e.id }))
          .execute();
        assert.deepEqual(
          direct.map((row) => row.id),
          expected,
        );
        const position = base.select(({ e }) => ({
          id: e.id,
          position: windowFunctions.rowNumber().over(windowSpec().orderBy(sort, e.id.asc())),
        }));
        const throughWindow = await db
          .from({ p: position.as() })
          .orderBy(({ p }) => p.position.asc())
          .select(({ p }) => ({ id: p.id }))
          .execute();
        assert.deepEqual(
          throughWindow.map((row) => row.id),
          expected,
        );
      }

    const daily = base
      .groupBy(({ e }) => ({ bucket: e.bucket }))
      .select(({ e }, _ext, keys) => ({ bucket: keys.bucket, total: aggregate.sum(e.weight) }))
      .asCte("window_daily");
    const dailyBase = db.from({ d: daily });
    const dayOrder = windowSpec().orderBy(dailyBase.scope.d.bucket.asc());
    const calculated = dailyBase
      .select(({ d }) => ({
        bucket: d.bucket,
        total: d.total,
        running: aggregate
          .sum(d.total)
          .over(dayOrder.rowsBetween(frame.unboundedPreceding, frame.currentRow)),
        global: aggregate.sum(d.total).over(windowSpec()),
        previous: windowFunctions.lag(d.total).over(dayOrder),
      }))
      .asCte("window_calculated");
    const shown = db
      .from({ r: calculated })
      .where(({ r }) => r.bucket.eq(2))
      .select(({ r }) => ({
        total: r.total,
        running: r.running,
        global: r.global,
        previous: r.previous,
      }));
    assert.deepEqual(await shown.execute(), [
      { total: "30", running: "60", global: "60", previous: "30" },
    ]);
    assert.equal(await shown.count(), 1n);
    assert.equal(await shown.limit(0).count(), 0n);
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
