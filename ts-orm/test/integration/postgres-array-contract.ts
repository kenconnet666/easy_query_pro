import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  column,
  createDatabase,
  dialect,
  identifier,
  pgArray,
  postgresTypes,
  sql,
  table,
  types,
  valueOf,
  WriteExecutionError,
} from "../../src/index.js";

export async function verifyPgArrays(db: ReturnType<typeof createDatabase>): Promise<void> {
  const name = "eqts_array_" + randomBytes(7).toString("hex");
  const integers = postgresTypes.array(types.integer().nullable());
  const texts = postgresTypes.array(types.text(100).nullable());
  const uuid = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA";
  const id = postgresTypes.uuid().map({
    identity: Symbol("array-id"),
    to: (value: { value: string }) => value.value,
    from: (value) => ({ value }),
  });
  const definition = table(name, {
    id: column(types.integer()),
    numbers: column(integers).nullable(),
    texts: column(texts).nullable(),
    big: column(postgresTypes.array(types.bigint())).defaulted(),
    amount: column(postgresTypes.array(types.decimal(19, 4))).defaulted(),
    ids: column(postgresTypes.array(id)).defaulted(),
    moments: column(postgresTypes.array(types.localDateTime())).defaulted(),
  });
  const special = [
    null,
    "NULL",
    "",
    "a,b",
    "{x}",
    'a"b',
    "a\\b",
    "  spaced  ",
    "中文😀",
    "\u00a0",
    "\u2003",
    "\ufeff",
    "x' OR 1=1 --",
  ];
  let created = false;
  try {
    await db.command(sql`CREATE TABLE ${identifier(name)} (
      ${identifier("id")} INTEGER PRIMARY KEY, ${identifier("numbers")} INTEGER[], ${identifier("texts")} VARCHAR(100)[],
      ${identifier("big")} BIGINT[] NOT NULL DEFAULT '{}', ${identifier("amount")} DECIMAL(19,4)[] NOT NULL DEFAULT '{}',
      ${identifier("ids")} UUID[] NOT NULL DEFAULT '{}', ${identifier("moments")} TIMESTAMP(6)[] NOT NULL DEFAULT '{}')`);
    created = true;
    await db
      .insert(definition)
      .values([
        {
          id: 1,
          numbers: [1, null, 2],
          texts: special,
          big: [9007199254740993n],
          amount: ["123456789012345.1234"],
          ids: [{ value: uuid }],
          moments: ["2024-03-10 02:30:00.123456"],
        },
        { id: 2, numbers: [], texts: [] },
        { id: 3, numbers: null, texts: null },
        { id: 4, numbers: [null], texts: [null] },
        { id: 5, numbers: [1, 1], texts: ["duplicate", "duplicate"] },
      ])
      .execute();
    const base = db.from({ a: definition });
    const first = await base
      .where(({ a }) => a.id.eq(1))
      .select(({ a }) => ({
        numbers: a.numbers,
        texts: a.texts,
        big: a.big,
        amount: a.amount,
        ids: a.ids,
        moments: a.moments,
      }))
      .first();
    assert.deepEqual(first, {
      numbers: [1, null, 2],
      texts: special,
      big: [9007199254740993n],
      amount: ["123456789012345.1234"],
      ids: [{ value: uuid.toLowerCase() }],
      moments: ["2024-03-10 02:30:00.123456"],
    });
    const results = await base
      .orderBy(({ a }) => a.id.asc())
      .select(({ a }) => {
        const array = a.numbers.mount(pgArray);
        return {
          hasOne: array.contains([1]),
          overlaps: array.overlaps([2]),
          any7: array.anyEquals(7),
          any1: array.anyEquals(1),
          hasNull: array.contains([null]),
          equalsNull: a.numbers.eq([null]),
          size: array.cardinality(),
          containsEmpty: array.contains([]),
          overlapsEmpty: array.overlaps([]),
        };
      })
      .execute();
    assert.deepEqual(results, [
      {
        hasOne: true,
        overlaps: true,
        any7: null,
        any1: true,
        hasNull: false,
        equalsNull: false,
        size: 3,
        containsEmpty: true,
        overlapsEmpty: false,
      },
      {
        hasOne: false,
        overlaps: false,
        any7: false,
        any1: false,
        hasNull: false,
        equalsNull: false,
        size: 0,
        containsEmpty: true,
        overlapsEmpty: false,
      },
      {
        hasOne: null,
        overlaps: null,
        any7: null,
        any1: null,
        hasNull: null,
        equalsNull: null,
        size: null,
        containsEmpty: null,
        overlapsEmpty: null,
      },
      {
        hasOne: false,
        overlaps: false,
        any7: null,
        any1: null,
        hasNull: false,
        equalsNull: true,
        size: 1,
        containsEmpty: true,
        overlapsEmpty: false,
      },
      {
        hasOne: true,
        overlaps: false,
        any7: false,
        any1: true,
        hasNull: false,
        equalsNull: false,
        size: 2,
        containsEmpty: true,
        overlapsEmpty: false,
      },
    ]);
    assert.equal(
      (
        await base
          .select(() => ({ contains: valueOf([1], integers).mount(pgArray).contains([1, 1]) }))
          .first()
      )?.contains,
      true,
    );
    const selected = base.select(({ a }) => ({ id: a.id, numbers: a.numbers }));
    const cte = selected.asCte("array_rows");
    assert.deepEqual(
      await db
        .from({ c: cte })
        .where(({ c }) => c.numbers.mount(pgArray).contains([1]))
        .orderBy(({ c }) => c.id.asc())
        .select(({ c }) => ({ id: c.id }))
        .execute(),
      [{ id: 1 }, { id: 5 }],
    );
    assert.equal(
      await db
        .from({ u: selected.union(selected) })
        .select(({ u }) => ({ id: u.id }))
        .count(),
      5n,
    );

    const scalar = db
      .from({ source: definition })
      .where(({ source }) => source.id.eq(1))
      .select(({ source }) => ({ numbers: source.numbers }))
      .scalar();
    const scalarResult = await base
      .where(({ a }) => a.id.eq(1))
      .select(() => ({ value: scalar, size: scalar.mount(pgArray).cardinality() }))
      .first();
    assert.deepEqual(scalarResult, { value: [1, null, 2], size: 3 });

    const huge = valueOf(
      Array.from({ length: 65_536 }, (_, i) => i),
      postgresTypes.array(types.integer()),
    )
      .mount(pgArray)
      .cardinality();
    const oneBinding = createDatabase({ dialect: dialect("postgres", { maxParameters: 1 }) })
      .from({ a: definition })
      .select(() => ({ size: huge }));
    assert.equal(oneBinding.compile().parameters.length, 1);
    assert.equal((await base.select(() => ({ size: huge })).first())?.size, 65_536);

    await db.transaction(async (tx) => {
      await tx.command(sql`SET LOCAL array_nulls = off`);
      await assert.rejects(
        tx
          .insert(definition)
          .values({ id: 99, texts: [null], numbers: [] })
          .execute(),
        (error) =>
          error instanceof WriteExecutionError &&
          error.cause instanceof TypeError &&
          /array_nulls=on/.test(error.cause.message),
      );
      await tx
        .insert(definition)
        .values({ id: 100, texts: ["NULL"], numbers: [] })
        .execute();
      assert.deepEqual(
        (
          await tx
            .from({ a: definition })
            .where(({ a }) => a.id.eq(100))
            .select(({ a }) => ({ texts: a.texts }))
            .first()
        )?.texts,
        ["NULL"],
      );
      await tx.command(sql`SET LOCAL array_nulls = on`);
      await tx
        .insert(definition)
        .values({ id: 99, texts: [null], numbers: [] })
        .execute();
    });
    assert.deepEqual(
      (
        await base
          .where(({ a }) => a.id.eq(99))
          .select(({ a }) => ({ texts: a.texts }))
          .first()
      )?.texts,
      [null],
    );

    for (const invalid of [sql`'[0:1]={1,2}'::integer[]`, sql`'{{1,2},{3,4}}'::integer[]`]) {
      await db.command(
        sql`UPDATE ${identifier(name)} SET ${identifier("numbers")} = ${invalid} WHERE ${identifier("id")} = 2`,
      );
      await assert.rejects(
        base
          .where(({ a }) => a.id.eq(2))
          .select(({ a }) => ({ numbers: a.numbers }))
          .execute(),
        /Cannot decode projection/,
      );
    }
    const strict = table(name, {
      id: column(types.integer()),
      numbers: column(postgresTypes.array(types.integer())).nullable(),
    });
    await assert.rejects(
      db
        .from({ a: strict })
        .where(({ a }) => a.id.eq(4))
        .select(({ a }) => ({ numbers: a.numbers }))
        .execute(),
      (error) =>
        error instanceof TypeError &&
        error.cause instanceof TypeError &&
        /array element at index 0/.test(error.cause.message),
    );
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
