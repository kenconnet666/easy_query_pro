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

export async function verifyStatistics(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
): Promise<void> {
  const name = "eqts_stats_" + randomBytes(7).toString("hex");
  const measurement = types.float64().map({
    identity: Symbol("measurement"),
    to: (value: { value: number }) => value.value,
    from: (value) => ({ value }),
  });
  const definition = table(name, {
    id: column(types.integer()),
    bucket: column(types.integer()),
    exact: column(types.decimal(19, 4)).nullable(),
    integer: column(types.integer()).nullable(),
    big: column(types.bigint()),
    observed: column(measurement).nullable(),
  });
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
