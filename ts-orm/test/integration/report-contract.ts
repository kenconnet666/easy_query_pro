import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  aggregate,
  caseWhen,
  coalesce,
  column,
  createDatabase,
  identifier,
  nullIf,
  sql,
  table,
  types,
  valueOf,
  type Dialect,
} from "../../src/index.js";

export async function verifyReports(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
): Promise<void> {
  const name = "eqts_r_" + randomBytes(7).toString("hex");
  const sales = table(name, {
    id: column(types.integer()),
    team: column(types.text(20)).nullable(),
    bucket: column(types.integer()),
    amount: column(types.decimal(19, 4)).nullable(),
    units: column(types.integer()),
    big: column(types.bigint()),
    active: column(types.boolean()).nullable(),
  });
  let created = false;
  try {
    const create = dialect.name === "hana" ? sql`CREATE COLUMN TABLE` : sql`CREATE TABLE`;
    const text = dialect.name === "hana" ? sql`NVARCHAR(20)` : sql`VARCHAR(20)`;
    await db.command(sql`${create} ${identifier(name)} (
      ${identifier("id")} INTEGER PRIMARY KEY,${identifier("team")} ${text},${identifier("bucket")} INTEGER,
      ${identifier("amount")} DECIMAL(19,4),${identifier("units")} INTEGER,${identifier("big")} BIGINT,${identifier("active")} BOOLEAN)`);
    created = true;
    await db
      .insert(sales)
      .values([
        {
          id: 1,
          team: "A",
          bucket: 1,
          amount: "10.0000",
          units: 2147483647,
          big: 9007199254740993n,
          active: true,
        },
        {
          id: 2,
          team: "A",
          bucket: 1,
          amount: "20.0000",
          units: 2147483647,
          big: 9007199254740993n,
          active: false,
        },
        { id: 3, team: "A", bucket: 2, amount: null, units: 0, big: 0n, active: null },
        { id: 4, team: "B", bucket: 1, amount: "5.0000", units: 1, big: 1n, active: true },
        { id: 5, team: "B", bucket: 2, amount: null, units: 0, big: 0n, active: false },
        { id: 6, team: null, bucket: 3, amount: null, units: 0, big: 0n, active: null },
      ])
      .execute();
    const base = db.from({ s: sales });
    const totals = await base
      .select(({ s }) => ({
        count: aggregate.count(),
        present: aggregate.count(s.amount),
        total: aggregate.sum(s.amount),
        units: aggregate.sum(s.units),
        big: aggregate.sum(s.big),
        activeCount: aggregate.count().filter(s.active),
        activeTotal: aggregate.sum(s.amount).filter(s.active),
        distinctTeams: aggregate.count(s.team).distinct(),
        min: aggregate.min(s.amount),
        max: aggregate.max(s.amount),
      }))
      .first();
    assert.deepEqual(totals, {
      count: 6n,
      present: 3n,
      total: "35.0000",
      units: "4294967295",
      big: "18014398509481987",
      activeCount: 2n,
      activeTotal: "15.0000",
      distinctTeams: 2n,
      min: "5.0000",
      max: "20.0000",
    });

    const empty = base
      .where(({ s }) => s.id.lt(0))
      .select(({ s }) => ({ count: aggregate.count(), total: aggregate.sum(s.amount) }));
    assert.deepEqual(await empty.execute(), [{ count: 0n, total: null }]);
    assert.equal(await empty.count(), 1n);
    assert.equal(
      await base
        .where(({ s }) => s.id.lt(0))
        .groupBy(({ s }) => ({ team: s.team }))
        .select(() => ({ count: aggregate.count() }))
        .count(),
      0n,
    );
    const grouped = base
      .groupBy(({ s }) => ({ team: s.team }))
      .having(({ s }) => aggregate.sum(s.amount).gt("10.0000"))
      .select(({ s }, _e, g) => ({
        team: g.team,
        total: aggregate.sum(s.amount),
        count: aggregate.count(),
      }));
    assert.deepEqual(await grouped.execute(), [{ team: "A", total: "30.0000", count: 3n }]);
    assert.equal(await grouped.count(), 1n);

    // 先聚合事实，再进入新的来源；累计值不被外层 SUM 再次窄 CAST。
    const daily = base
      .groupBy(({ s }) => ({ team: s.team, bucket: s.bucket }))
      .select(({ s }, _e, g) => ({
        team: g.team,
        bucket: g.bucket,
        total: aggregate.sum(s.amount),
        facts: aggregate.count(),
      }))
      .asCte("daily_sales");
    const report = db
      .from({ d: daily })
      .groupBy(({ d }) => ({ team: d.team }))
      .select(({ d }, _e, g) => ({
        team: g.team,
        total: aggregate.sum(d.total),
        facts: aggregate.sum(d.facts),
      }));
    const reportRows = await report.execute();
    assert.equal(reportRows.length, 3);
    assert.deepEqual(
      reportRows.find((row) => row.team === "A"),
      { team: "A", total: "30.0000", facts: "3" },
    );
    assert.deepEqual(
      reportRows.find((row) => row.team === null),
      { team: null, total: null, facts: "1" },
    );
    assert.equal(await report.count(), 3n);
    const oneDay = db
      .from({ d: daily })
      .where(({ d }) => d.bucket.eq(2))
      .select(({ d }) => ({ total: coalesce(d.total, valueOf("0", types.decimal(38, 4))) }));
    assert.deepEqual(await oneDay.execute(), [{ total: "0.0000" }, { total: "0.0000" }]);
    const union = db.from({ r: grouped.unionAll(grouped) }).select(({ r }) => ({ total: r.total }));
    assert.deepEqual(await union.execute(), [{ total: "30.0000" }, { total: "30.0000" }]);

    const left = await base
      .where(({ s }) => s.id.eq(6))
      .leftJoin({ other: sales }, ({ s, other }) => s.id.eq(other.id.add(100)))
      .select(({ other }) => ({ all: aggregate.count(), matched: aggregate.count(other.id) }))
      .first();
    assert.deepEqual(left, { all: 1n, matched: 0n });
    const choices = await base
      .where(({ s }) => s.id.lte(3))
      .orderBy(({ s }) => s.id.asc())
      .select(({ s }) => ({
        label: caseWhen(s.active, valueOf("yes", types.text(20)), valueOf("no", types.text(20))),
        nonzero: nullIf(s.units, valueOf(0, types.integer())),
      }))
      .execute();
    assert.deepEqual(choices, [
      { label: "yes", nonzero: 2147483647 },
      { label: "no", nonzero: 2147483647 },
      { label: "no", nonzero: null },
    ]);

    const expanded = await base
      .where(({ s }) => s.id.lte(2))
      .select(() => ({
        total: aggregate.sum(valueOf("999999999999999.9999", types.decimal(19, 4))),
      }))
      .first();
    assert.equal(expanded!.total, "1999999999999999.9998");
    const max = "9".repeat(38);
    assert.equal(
      (await base
        .where(({ s }) => s.id.eq(1))
        .select(() => ({ total: aggregate.sum(valueOf(max, types.decimal(38, 0))) }))
        .first())!.total,
      max,
    );
    // HANA 在累加时拒绝溢出，PG/MySQL 宽结果在 decoder 边界拒绝，均不能返回夹值。
    await assert.rejects(
      base
        .where(({ s }) => s.id.lte(2))
        .select(() => ({ total: aggregate.sum(valueOf(max, types.decimal(38, 0))) }))
        .execute(),
      (error) => {
        assert.ok(error instanceof Error);
        if (dialect.name === "hana") assert.match(error.message, /overflow|out of range/i);
        else {
          assert.match(error.message, /Cannot decode projection/);
          assert.ok(error.cause instanceof RangeError);
        }
        return true;
      },
    );
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
