import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  aggregate,
  coalesce,
  column,
  createDatabase,
  grouping,
  identifier,
  or,
  sql,
  table,
  types,
  valueOf,
  type Dialect,
} from "../../src/index.js";

export async function verifyRollup(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
): Promise<void> {
  const name = "eqts_rollup_" + randomBytes(7).toString("hex");
  const country = types.text(20).map({
    identity: Symbol("country"),
    to: (value: { code: string }) => value.code,
    from: (code) => ({ code }),
  });
  const definition = table(name, {
    id: column(types.integer()),
    country: column(country),
    city: column(types.text(20)).nullable(),
    amount: column(types.decimal(19, 4)).nullable(),
  });
  const text = dialect.name === "hana" ? sql`NVARCHAR(20)` : sql`VARCHAR(20)`;
  let created = false;
  try {
    await db.command(
      sql`CREATE TABLE ${identifier(name)} (${identifier("id")} INTEGER PRIMARY KEY, ${identifier("country")} ${text} NOT NULL, ${identifier("city")} ${text}, ${identifier("amount")} DECIMAL(19,4))`,
    );
    created = true;
    const nativeKeys =
      dialect.name === "mysql"
        ? sql`${identifier("country")}, ${identifier("city")} WITH ROLLUP`
        : sql`ROLLUP (${identifier("country")}, ${identifier("city")})`;
    const nativeEmpty = await db.command(
      sql`SELECT ${identifier("country")}, ${identifier("city")}, COUNT(*) AS ${identifier("count")} FROM ${identifier(name)} GROUP BY ${nativeKeys}`,
    );
    assert.equal(nativeEmpty.rows.length, dialect.name === "postgres" ? 1 : 0);
    const base = db.from({ s: definition });
    const rolled = base.rollupBy(({ s }) => ({ country: s.country, city: s.city }));
    const report = rolled
      .orderBy((_, __, keys) => [
        grouping(keys.country).asc(),
        keys.country.asc(),
        grouping(keys.city).asc(),
        keys.city.asc("first"),
      ])
      .select(({ s }, _, keys) => ({
        country: keys.country,
        city: keys.city,
        countryGroup: grouping(keys.country),
        cityGroup: grouping(keys.city),
        count: aggregate.count(),
        total: aggregate.sum(s.amount),
      }));
    assert.deepEqual(await report.execute(), []);
    assert.equal(await report.count(), 0n);
    await db
      .insert(definition)
      .values([
        { id: 1, country: { code: "A" }, city: "X", amount: "10" },
        { id: 2, country: { code: "A" }, city: "X", amount: "5" },
        { id: 3, country: { code: "A" }, city: null, amount: "7" },
        { id: 4, country: { code: "A" }, city: "Y", amount: null },
        { id: 5, country: { code: "B" }, city: "Z", amount: "20" },
      ])
      .execute();
    const rows = await report.execute();
    assert.deepEqual(rows, [
      {
        country: { code: "A" },
        city: null,
        countryGroup: 0,
        cityGroup: 0,
        count: 1n,
        total: "7.0000",
      },
      {
        country: { code: "A" },
        city: "X",
        countryGroup: 0,
        cityGroup: 0,
        count: 2n,
        total: "15.0000",
      },
      { country: { code: "A" }, city: "Y", countryGroup: 0, cityGroup: 0, count: 1n, total: null },
      {
        country: { code: "A" },
        city: null,
        countryGroup: 0,
        cityGroup: 1,
        count: 4n,
        total: "22.0000",
      },
      {
        country: { code: "B" },
        city: "Z",
        countryGroup: 0,
        cityGroup: 0,
        count: 1n,
        total: "20.0000",
      },
      {
        country: { code: "B" },
        city: null,
        countryGroup: 0,
        cityGroup: 1,
        count: 1n,
        total: "20.0000",
      },
      { country: null, city: null, countryGroup: 1, cityGroup: 1, count: 5n, total: "42.0000" },
    ]);
    assert.equal(await report.count(), 7n);
    const page = await report.page(3, 3);
    assert.equal(page.total, 7n);
    assert.deepEqual(page.rows, [rows[6]]);
    const having = rolled
      .having(({ s }, _, keys) =>
        or(aggregate.sum(s.amount).gt("10.00"), grouping(keys.country).eq(1)),
      )
      .select((_, __, keys) => ({ country: keys.country }));
    assert.equal(await having.count(), 5n);
    assert.deepEqual(await having.where(({ s }) => s.id.lt(0)).execute(), []);
    const nativeFilteredEmpty = await db.command(
      sql`SELECT ${identifier("country")}, ${identifier("city")}, COUNT(*) AS ${identifier("count")} FROM ${identifier(name)} WHERE 1=0 GROUP BY ${nativeKeys}`,
    );
    assert.equal(nativeFilteredEmpty.rows.length, dialect.name === "postgres" ? 1 : 0);

    const cte = report.asCte("subtotal_rows");
    const totals = db
      .from({ r: cte })
      .where(({ r }) => r.cityGroup.eq(1))
      .orderBy(({ r }) => [r.countryGroup.asc(), r.country.asc()])
      .select(({ r }) => ({ country: r.country, total: r.total }));
    assert.equal(await totals.count(), 3n);
    assert.deepEqual((await totals.page(2, 2)).rows, [{ country: null, total: "42.0000" }]);
    const markers = await db
      .from({ r: cte })
      .select(({ r }) => ({ markers: aggregate.sum(r.cityGroup) }))
      .first();
    assert.equal(markers!.markers, "3");
    const labels = await rolled
      .orderBy((_, __, keys) => grouping(keys.country).desc())
      .select((_, __, keys) => ({
        country: coalesce(keys.country, valueOf({ code: "TOTAL" }, country)),
      }))
      .first();
    assert.deepEqual(labels, { country: { code: "TOTAL" } });

    // 计算键先变成真正的派生列，三库的 GROUPING 都引用同一查询层列而非猜测表达式等价。
    const computed = base.select(({ s }) => ({ upper: s.country.upper() })).as();
    const byUpper = await db
      .from({ c: computed })
      .rollupBy(({ c }) => ({ upper: c.upper }))
      .orderBy((_, __, keys) => [grouping(keys.upper).asc(), keys.upper.asc()])
      .select((_, __, keys) => ({ upper: keys.upper, count: aggregate.count() }))
      .execute();
    assert.deepEqual(byUpper, [
      { upper: "A", count: 4n },
      { upper: "B", count: 1n },
      { upper: null, count: 5n },
    ]);
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
