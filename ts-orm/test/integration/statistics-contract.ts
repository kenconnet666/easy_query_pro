import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  aggregate,
  column,
  createDatabase,
  frame,
  identifier,
  nativeCast,
  sql,
  table,
  types,
  valueOf,
  windowSpec,
  type Dialect,
} from "../../src/index.js";

function near(actual: number | null | undefined, expected: number): void {
  assert.equal(typeof actual, "number");
  assert.ok(
    Math.abs(actual! - expected) <= Math.max(1, Math.abs(expected)) * 1e-13,
    `Expected approximately ${expected}, got ${actual}`,
  );
}

function statisticsTable(name: string) {
  const measurement = types.float64().map({
    identity: Symbol("measurement"),
    to: (value: { value: number }) => value.value,
    from: (value) => ({ value }),
  });
  return table(name, {
    id: column(types.integer()),
    bucket: column(types.integer()),
    exact: column(types.decimal(19, 4)).nullable(),
    integer: column(types.integer()).nullable(),
    big: column(types.bigint()),
    observed: column(measurement).nullable(),
  });
}

export async function verifyStatistics(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
): Promise<void> {
  const name = "eqts_stats_" + randomBytes(7).toString("hex");
  const definition = statisticsTable(name);
  const float = dialect.name === "postgres" ? sql`DOUBLE PRECISION` : sql`DOUBLE`;
  let created = false;
  try {
    await db.command(
      sql`CREATE TABLE ${identifier(name)} (${identifier("id")} INTEGER PRIMARY KEY, ${identifier("bucket")} INTEGER, ${identifier("exact")} DECIMAL(19,4), ${identifier("integer")} INTEGER, ${identifier("big")} BIGINT, ${identifier("observed")} ${float})`,
    );
    created = true;
    await db
      .insert(definition)
      .values([
        {
          id: 1,
          bucket: 1,
          exact: "1",
          integer: 1,
          big: 9007199254740992n,
          observed: { value: 1 },
        },
        {
          id: 2,
          bucket: 1,
          exact: "1",
          integer: 1,
          big: 9007199254740993n,
          observed: { value: 1 },
        },
        { id: 3, bucket: 1, exact: "5.0001", integer: 5, big: 0n, observed: { value: 5 } },
        { id: 4, bucket: 2, exact: null, integer: null, big: 0n, observed: null },
        { id: 5, bucket: 2, exact: "-2", integer: -2, big: 0n, observed: { value: -2 } },
      ])
      .execute();
    const base = db.from({ s: definition });
    const summary = base.select(({ s }) => ({
      exact: aggregate.avgApprox(s.exact),
      integer: aggregate.avgApprox(s.integer),
      observed: aggregate.avgApprox(s.observed),
      distinct: aggregate.avgApprox(s.observed).distinct(),
      filtered: aggregate.avgApprox(s.observed).filter(s.id.lte(3)),
      absent: aggregate.avgApprox(s.observed).filter(s.id.lt(0)),
      minimum: aggregate.min(s.observed),
      maximum: aggregate.max(s.observed),
    }));
    const result = await summary.first();
    near(result!.exact, 1.250025);
    near(result!.integer, 1.25);
    near(result!.observed, 1.25);
    near(result!.distinct, 4 / 3);
    near(result!.filtered, 7 / 3);
    assert.equal(result!.absent, null);
    assert.deepEqual(result!.minimum, { value: -2 });
    assert.deepEqual(result!.maximum, { value: 5 });
    assert.deepEqual(
      await base
        .where(({ s }) => s.id.lt(0))
        .select(({ s }) => ({ mean: aggregate.avgApprox(s.observed) }))
        .first(),
      { mean: null },
    );
    assert.deepEqual(
      await base
        .where(({ s }) => s.id.eq(4))
        .select(({ s }) => ({ mean: aggregate.avgApprox(s.observed) }))
        .first(),
      { mean: null },
    );

    const order = windowSpec().orderBy(base.scope.s.id.asc());
    const windows = await base
      .orderBy(({ s }) => s.id.asc())
      .select(({ s }) => ({
        rolling: aggregate
          .avgApprox(s.observed)
          .over(order.rowsBetween(frame.preceding(1), frame.currentRow)),
        next: aggregate
          .avgApprox(s.observed)
          .over(order.rowsBetween(frame.following(1), frame.following(1))),
      }))
      .execute();
    [1, 1, 3, 5, -2].forEach((expected, index) => near(windows[index]!.rolling, expected));
    assert.deepEqual(
      windows.map((row) => row.next),
      [1, 5, null, -2, null],
    );
    const groups = base
      .groupBy(({ s }) => ({ bucket: s.bucket }))
      .having(({ s }) => aggregate.avgApprox(s.observed).gt(0))
      .select(({ s }, _, keys) => ({ bucket: keys.bucket, mean: aggregate.avgApprox(s.observed) }))
      .asCte("sample_means");
    const grouped = await db
      .from({ g: groups })
      .orderBy(({ g }) => g.bucket.asc())
      .select(({ g }) => ({ bucket: g.bucket, mean: g.mean }))
      .page(1, 10);
    assert.equal(grouped.total, 1n);
    near(grouped.rows[0]!.mean, 7 / 3);
    const preciseSource = base
      .where(({ s }) => s.id.lte(3))
      .select(({ s }) => ({ big: s.big }))
      .distinct()
      .as();
    const before = await db
      .from({ d: preciseSource })
      .select(({ d }) => ({ mean: aggregate.avgApprox(d.big) }))
      .first();
    const after = await base
      .where(({ s }) => s.id.lte(3))
      .select(({ s }) => ({ mean: aggregate.avgApprox(s.big).distinct() }))
      .first();
    near(before!.mean, 2 ** 54 / 3);
    near(after!.mean, 2 ** 52);
    assert.notEqual(
      before!.mean,
      after!.mean,
      "DISTINCT after approximate conversion intentionally merges neighboring large integers",
    );

    const raw = await db.command(
      sql`SELECT ${identifier("observed")} AS ${identifier("value")} FROM ${identifier(name)} WHERE ${identifier("id")} = 3`,
    );
    assert.equal(typeof raw.rows[0]!.value, dialect.name === "postgres" ? "string" : "number");
    if (dialect.name === "mysql") {
      await db.transaction(async (tx) => {
        const settings = await tx.command(
          sql`SELECT @@SESSION.div_precision_increment AS ${identifier("value")}`,
        );
        const previous = types.integer().decode(settings.rows[0]!.value);
        try {
          for (const increment of [0, 30]) {
            await tx.command(
              sql`SET SESSION div_precision_increment = ${valueOf(increment, types.integer())}`,
            );
            const value = await tx
              .from({ s: definition })
              .select(({ s }) => ({ mean: aggregate.avgApprox(s.exact) }))
              .first();
            near(value!.mean, 1.250025);
          }
        } finally {
          await tx.command(
            sql`SET SESSION div_precision_increment = ${valueOf(previous, types.integer())}`,
          );
        }
      });
    }

    await verifyDispersion(db, dialect, definition);
    const preciseFloat = 1.2345678901234567;
    await db
      .update(definition)
      .set({ observed: { value: preciseFloat } })
      .where((s) => s.id.eq(1))
      .execute();
    const roundTrip = await base
      .where(({ s }) => s.id.eq(1))
      .select(({ s }) => ({ observed: s.observed }))
      .first();
    assert.equal(roundTrip!.observed?.value, preciseFloat);
    if (dialect.name === "postgres") {
      await db.transaction(async (tx) => {
        await tx.command(sql`SET LOCAL extra_float_digits = 0`);
        const legacy = await tx
          .from({ s: definition })
          .where(({ s }) => s.id.eq(1))
          .select(({ s }) => ({ observed: s.observed }))
          .first();
        assert.notEqual(
          legacy!.observed?.value,
          preciseFloat,
          "Legacy PG text formatting loses digits before the codec receives them",
        );
      });
      assert.equal(
        (await base
          .where(({ s }) => s.id.eq(1))
          .select(({ s }) => ({ observed: s.observed }))
          .first())!.observed?.value,
        preciseFloat,
      );
    }
    for (const edge of [
      Number.MAX_VALUE,
      2.2250738585072014e-308,
      Number.MIN_VALUE,
      1e100,
      1e-100,
      -0,
    ]) {
      await db
        .update(definition)
        .set({ observed: { value: edge } })
        .where((s) => s.id.eq(1))
        .execute();
      const result = await base
        .where(({ s }) => s.id.eq(1))
        .select(({ s }) => ({ value: s.observed }))
        .first();
      assert.equal(result!.value?.value, edge === 0 ? 0 : edge);
    }
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}

/** 与 AVG 共用数据和连接生命周期；固定数学结果与直接原生 SQL 双重验证四项统计。 */
async function verifyDispersion(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
  definition: ReturnType<typeof statisticsTable>,
): Promise<void> {
  const base = db.from({ s: definition });
  const float = dialect.name === "postgres" ? sql`DOUBLE PRECISION` : sql`DOUBLE`;
  const variants = [
    [aggregate.variancePopulationApprox, sql`VAR_POP`, true, false],
    [aggregate.varianceSampleApprox, sql`VAR_SAMP`, false, false],
    [aggregate.standardDeviationPopulationApprox, sql`STDDEV_POP`, true, true],
    [aggregate.standardDeviationSampleApprox, sql`STDDEV_SAMP`, false, true],
  ] as const;
  for (const [statistic, keyword, population, deviation] of variants) {
    const expected = (variance: number) => (deviation ? Math.sqrt(variance) : variance);
    const result = await base
      .select(({ s }) => ({
        value: statistic(s.observed),
        filtered: statistic(s.observed).filter(s.id.lte(3)),
        absent: statistic(s.observed).filter(s.id.lt(0)),
      }))
      .first();
    near(result!.value, expected(population ? 6.1875 : 8.25));
    near(result!.filtered, expected(population ? 32 / 9 : 16 / 3));
    assert.equal(result!.absent, null);
    const native = await db.command(
      sql`SELECT ${keyword}(CAST(${identifier("observed")} AS ${float})) AS ${identifier("value")} FROM ${identifier(definition.name)}`,
    );
    near(result!.value, types.float64().decode(native.rows[0]!.value));
    for (const id of [0, 1, 4]) {
      const single = await base
        .where(({ s }) => s.id.eq(id))
        .select(({ s }) => ({ value: statistic(s.observed) }))
        .first();
      assert.equal(single!.value, id === 1 && population ? 0 : null);
    }
    const tiny = await base
      .where(({ s }) => s.id.lte(2))
      .select(({ s }) => ({
        value: statistic(nativeCast(s.id, types.decimal(19, 4)).multiply("0.0001")),
        rounded: statistic(s.big),
      }))
      .first();
    near(tiny!.value, expected(population ? 2.5e-9 : 5e-9));
    assert.equal(tiny!.rounded, 0);

    const order = windowSpec().orderBy(base.scope.s.id.asc());
    const windows = base
      .select(({ s }) => ({
        id: s.id,
        cumulative: statistic(s.observed).over(
          order.rowsBetween(frame.unboundedPreceding, frame.currentRow),
        ),
        current: statistic(s.observed).over(order.rowsBetween(frame.currentRow, frame.currentRow)),
        next: statistic(s.observed).over(order.rowsBetween(frame.following(1), frame.following(1))),
      }))
      .asCte("dispersion_windows");
    const report = db
      .from({ w: windows })
      .orderBy(({ w }) => w.id.asc())
      .select(({ w }) => ({
        id: w.id,
        cumulative: w.cumulative,
        current: w.current,
        next: w.next,
      }));
    const rows = await report.execute();
    const cumulative = population
      ? [0, 0, 32 / 9, 32 / 9, 6.1875]
      : [null, 0, 16 / 3, 16 / 3, 8.25];
    rows.forEach((row, index) => {
      const variance = cumulative[index];
      if (variance === null) assert.equal(row.cumulative, null);
      else near(row.cumulative, expected(variance!));
      assert.equal(row.current, population && index !== 3 ? 0 : null);
      assert.equal(row.next, population && index !== 2 && index !== 4 ? 0 : null);
    });
    assert.equal(await report.count(), 5n);
    assert.deepEqual(
      (await report.page(2, 3)).rows.map((row) => row.id),
      [4, 5],
    );
    const groups = await base
      .groupBy(({ s }) => ({ bucket: s.bucket }))
      .having(({ s }) => statistic(s.observed).gt(1))
      .select(({ s }, _, keys) => ({ bucket: keys.bucket, value: statistic(s.observed) }))
      .execute();
    assert.equal(groups.length, 1);
    assert.equal(groups[0]!.bucket, 1);
    near(groups[0]!.value, expected(population ? 32 / 9 : 16 / 3));
    const distinct = base
      .select(({ s }) => ({ observed: s.observed }))
      .distinct()
      .as();
    const deduplicated = await db
      .from({ d: distinct })
      .select(({ d }) => ({ value: statistic(d.observed) }))
      .first();
    near(deduplicated!.value, expected(population ? 74 / 9 : 37 / 3));
  }
}
