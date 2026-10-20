import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  aggregate,
  column,
  createDatabase,
  identifier,
  nativeCast,
  sql,
  table,
  types,
  valueOf,
  windowFunctions,
  windowSpec,
  type Dialect,
} from "../../src/index.js";

export async function verifyTemporal(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
  alternate: ReturnType<typeof createDatabase>,
): Promise<void> {
  const name = "eqts_time_" + randomBytes(7).toString("hex");
  const definition = table(name, {
    id: column(types.integer()),
    day: column(types.date()),
    occurred: column(types.localDateTime()).nullable(),
  });
  const input = [
    { id: 1, day: "2000-02-29", occurred: "2024-03-10 02:30:00.123456" },
    { id: 2, day: "9999-12-31", occurred: "2024-11-03T01:30:00" },
    { id: 3, day: "1000-01-01", occurred: null },
  ];
  const expected = input.map((row) => ({
    ...row,
    occurred: row.occurred === null ? null : types.localDateTime().encode(row.occurred),
  }));
  let created = false;
  try {
    const create = dialect.name === "hana" ? sql`CREATE COLUMN TABLE` : sql`CREATE TABLE`;
    const timestamp =
      dialect.name === "mysql"
        ? sql`DATETIME(6)`
        : dialect.name === "hana"
          ? sql`TIMESTAMP`
          : sql`TIMESTAMP(6) WITHOUT TIME ZONE`;
    await db.command(
      sql`${create} ${identifier(name)} (${identifier("id")} INTEGER PRIMARY KEY, ${identifier("day")} DATE NOT NULL, ${identifier("occurred")} ${timestamp})`,
    );
    created = true;
    await db.insert(definition).values(input).execute();
    assert.deepEqual(
      await alternate
        .from({ e: definition })
        .orderBy(({ e }) => e.id.asc())
        .select(({ e }) => ({ id: e.id, day: e.day, occurred: e.occurred }))
        .execute(),
      expected,
    );
    const base = db.from({ e: definition });
    const selected = base
      .orderBy(({ e }) => e.id.asc())
      .select(({ e }) => ({ id: e.id, day: e.day, occurred: e.occurred }));
    const previousTz = process.env.TZ;
    try {
      for (const timezone of ["UTC", "America/New_York", "Asia/Shanghai"]) {
        process.env.TZ = timezone;
        assert.deepEqual(await selected.execute(), expected);
      }
    } finally {
      if (previousTz === undefined) delete process.env.TZ;
      else process.env.TZ = previousTz;
    }
    assert.equal(await selected.where(({ e }) => e.day.eq("2000-02-29")).count(), 1n);
    assert.deepEqual(
      await base
        .groupBy(({ e }) => ({ day: e.day }))
        .orderBy((_s, _x, group) => group.day.asc())
        .select((_s, _x, group) => ({ day: group.day, count: aggregate.count() }))
        .execute(),
      [
        { day: "1000-01-01", count: 1n },
        { day: "2000-02-29", count: 1n },
        { day: "9999-12-31", count: 1n },
      ],
    );
    assert.deepEqual(
      await base
        .select(({ e }) => ({ day: e.day }))
        .distinct()
        .orderBy(({ e }) => e.day.asc())
        .execute(),
      [{ day: "1000-01-01" }, { day: "2000-02-29" }, { day: "9999-12-31" }],
    );
    assert.equal(
      await selected.where(({ e }) => e.occurred.eq("2024-03-10T02:30:00.123456")).count(),
      1n,
    );
    const derived = selected.asCte("temporal_rows");
    assert.deepEqual(
      await db
        .from({ t: derived })
        .orderBy(({ t }) => t.id.asc())
        .select(({ t }) => ({ id: t.id, day: t.day, occurred: t.occurred }))
        .execute(),
      expected,
    );
    assert.equal(
      await db
        .from({ u: selected.union(selected) })
        .select(({ u }) => ({ day: u.day }))
        .count(),
      3n,
    );
    assert.deepEqual(
      await base
        .select(({ e }) => ({ first: aggregate.min(e.occurred), last: aggregate.max(e.day) }))
        .first(),
      { first: expected[0]!.occurred, last: "9999-12-31" },
    );
    const order = windowSpec().orderBy(base.scope.e.id.asc());
    assert.deepEqual(
      await base
        .orderBy(({ e }) => e.id.asc())
        .select(({ e }) => ({ previous: windowFunctions.lag(e.occurred).over(order) }))
        .execute(),
      [
        { previous: null },
        { previous: expected[0]!.occurred },
        { previous: expected[1]!.occurred },
      ],
    );
    assert.deepEqual(
      await base
        .orderBy(({ e }) => e.id.asc())
        .select(({ e }) => ({ day: nativeCast(e.occurred, types.date()) }))
        .execute(),
      [{ day: "2024-03-10" }, { day: "2024-11-03" }, { day: null }],
    );
    assert.deepEqual(
      await base
        .select(() => ({
          day: valueOf(null, types.date().nullable()),
          occurred: valueOf(null, types.localDateTime().nullable()),
        }))
        .first(),
      { day: null, occurred: null },
    );
    await db
      .update(definition)
      .set({ occurred: "2024-11-03 01:30:00.000001" })
      .where((row) => row.id.eq(2))
      .execute();
    assert.equal(
      (await selected.where(({ e }) => e.id.eq(2)).first())?.occurred,
      "2024-11-03 01:30:00.000001",
    );
    if (dialect.name === "postgres") {
      await db.transaction(async (tx) => {
        await tx.command(sql`SET LOCAL DateStyle = 'SQL, DMY'`);
        await assert.rejects(
          tx
            .from({ e: definition })
            .select(({ e }) => ({ day: e.day }))
            .execute(),
          /Cannot decode projection/,
        );
      });
      assert.deepEqual((await selected.where(({ e }) => e.id.eq(1)).first())?.day, "2000-02-29");
    }
    if (dialect.name === "hana") {
      const empty = (
        await db.command(
          sql`SELECT CAST('' AS DATE) AS "empty_day", CAST('' AS TIMESTAMP) AS "empty_time", CASE WHEN CAST('' AS DATE) IS NULL OR CAST('' AS TIMESTAMP) IS NULL THEN 1 ELSE 0 END AS "sql_null" FROM DUMMY`,
        )
      ).rows[0]!;
      assert.equal(empty.sql_null, 0);
      assert.notEqual(empty.empty_day, null);
      assert.notEqual(empty.empty_time, null);
      assert.throws(() => types.date().nullable().decode(empty.empty_day));
      assert.throws(() => types.localDateTime().nullable().decode(empty.empty_time));
      await db.command(
        sql`UPDATE ${identifier(name)} SET ${identifier("occurred")} = TO_TIMESTAMP('2024-03-10 02:30:00.1234567', 'YYYY-MM-DD HH24:MI:SS.FF7') WHERE ${identifier("id")} = 1`,
      );
      await assert.rejects(
        selected.where(({ e }) => e.id.eq(1)).execute(),
        (error: unknown) =>
          error instanceof TypeError &&
          error.cause instanceof RangeError &&
          /discard precision/.test(error.cause.message),
      );
    }
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
