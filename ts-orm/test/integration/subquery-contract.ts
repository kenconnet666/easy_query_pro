import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
  aggregate,
  and,
  column,
  createDatabase,
  identifier,
  not,
  sql,
  table,
  types,
  type Dialect,
} from "../../src/index.js";

export async function verifySubqueries(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
): Promise<void> {
  const name = "eqts_subquery_" + randomBytes(7).toString("hex");
  const money = types.decimal(19, 4).map({
    identity: Symbol("query-money"),
    to: (value: { amount: string }) => value.amount,
    from: (amount) => ({ amount }),
  });
  const definition = table(name, {
    id: column(types.integer()),
    ownerId: column(types.integer()).nullable().named("owner_id"),
    amount: column(money).nullable(),
  });
  let created = false;
  try {
    await db.command(
      sql`CREATE TABLE ${identifier(name)} (${identifier("id")} INTEGER PRIMARY KEY, ${identifier("owner_id")} INTEGER, ${identifier("amount")} DECIMAL(19,4))`,
    );
    created = true;
    await db
      .insert(definition)
      .values([
        { id: 1, ownerId: null, amount: null },
        { id: 2, ownerId: null, amount: null },
        { id: 3, ownerId: null, amount: null },
        { id: 10, ownerId: 1, amount: { amount: "123456789012345.1200" } },
        { id: 11, ownerId: 1, amount: { amount: "0.0100" } },
        { id: 12, ownerId: 2, amount: null },
        { id: 21, ownerId: 10, amount: null },
      ])
      .execute();
    const base = db.from({ p: definition }).where(({ p }) => p.id.lt(10));
    const report = base
      .orderBy(({ p }) => p.id.asc())
      .select(({ p }) => {
        const child = db.from({ p: definition }).where(({ p: c }) => c.ownerId.eq(p.id));
        const exists = child.select(({ p: c }) => ({ id: c.id })).exists();
        return {
          id: p.id,
          exists,
          absent: not(exists),
          count: child.select(() => ({ count: aggregate.count() })).scalar(),
          amount: child.select(({ p: c }) => ({ total: aggregate.sum(c.amount) })).scalar(),
          exact: child
            .where(({ p: c }) => c.id.eq(10))
            .select(({ p: c }) => ({ value: c.amount }))
            .scalar(),
        };
      });
    const rows = await report.execute();
    assert.deepEqual(
      rows.map(({ id, exists, absent, count, exact }) => ({ id, exists, absent, count, exact })),
      [
        {
          id: 1,
          exists: true,
          absent: false,
          count: 2n,
          exact: { amount: "123456789012345.1200" },
        },
        { id: 2, exists: true, absent: false, count: 1n, exact: null },
        { id: 3, exists: false, absent: true, count: 0n, exact: null },
      ],
    );
    assert.match(rows[0]!.amount!, /^123456789012345\.13(?:0*)$/);
    assert.equal(rows[1]!.amount, null);
    assert.equal(rows[2]!.amount, null);
    assert.equal(await report.count(), 3n);
    assert.deepEqual(
      (await report.page(2, 2)).rows.map((row) => row.id),
      [3],
    );

    const nested = base
      .where(({ p }) =>
        db
          .from({ p: definition })
          .where(({ p: child }) =>
            and(
              child.ownerId.eq(p.id),
              db
                .from({ p: definition })
                .where(({ p: grandchild }) => grandchild.ownerId.eq(child.id))
                .select(({ p: grandchild }) => ({ id: grandchild.id }))
                .exists(),
            ),
          )
          .select(({ p: child }) => ({ id: child.id }))
          .exists(),
      )
      .select(({ p }) => ({ id: p.id }));
    assert.deepEqual(await nested.execute(), [{ id: 1 }]);

    const emptyCount = db
      .from({ empty: definition })
      .where(({ empty }) => empty.id.lt(0))
      .select(() => ({ count: aggregate.count() }))
      .exists();
    const distinct = db
      .from({ d: definition })
      .where(({ d }) => d.ownerId.isNotNull())
      .select(({ d }) => ({ owner: d.ownerId }))
      .distinct();
    const limited = db
      .from({ z: definition })
      .select(({ z }) => ({ id: z.id }))
      .limit(0)
      .exists();
    const semantics = await base
      .where(({ p }) => p.id.eq(1))
      .select(() => ({
        aggregateExists: emptyCount,
        distinctOffset: distinct.limit(1, 2).exists(),
        pastEnd: distinct.limit(1, 3).exists(),
        limitZero: limited,
      }))
      .first();
    assert.deepEqual(semantics, {
      aggregateExists: true,
      distinctOffset: true,
      pastEnd: false,
      limitZero: false,
    });

    const cte = db
      .from({ source: definition })
      .where(({ source }) => source.id.gte(10))
      .select(({ source }) => ({ id: source.id, owner: source.ownerId }))
      .asCte("eligible_children");
    const hasChild = base
      .where(({ p }) =>
        db
          .from({ c: cte })
          .where(({ c }) => c.owner.eq(p.id))
          .select(({ c }) => ({ id: c.id }))
          .exists(),
      )
      .orderBy(({ p }) => p.id.asc())
      .select(({ p }) => ({ id: p.id }));
    assert.deepEqual(await hasChild.execute(), [{ id: 1 }, { id: 2 }]);
    assert.equal(await hasChild.count(), 2n);

    const grouped = db
      .from({ c: definition })
      .where(({ c }) => c.ownerId.isNotNull())
      .groupBy(({ c }) => ({ owner: c.ownerId }))
      .select((_, __, keys) => ({ owner: keys.owner, count: aggregate.count() }))
      .asCte("owner_counts");
    const summaries = await db
      .from({ g: grouped })
      .orderBy(({ g }) => g.owner.asc())
      .select(({ g }) => ({
        owner: g.owner,
        count: g.count,
        parentExists: db
          .from({ p: definition })
          .where(({ p }) => g.owner.eq(p.id))
          .select(({ p }) => ({ id: p.id }))
          .exists(),
      }))
      .execute();
    assert.deepEqual(summaries, [
      { owner: 1, count: 2n, parentExists: true },
      { owner: 2, count: 1n, parentExists: true },
      { owner: 10, count: 1n, parentExists: true },
    ]);

    const many = db
      .from({ c: definition })
      .where(({ c }) => c.ownerId.eq(1))
      .select(({ c }) => ({ id: c.id }))
      .scalar();
    await assert.rejects(base.select(() => ({ value: many })).execute(), (error) => {
      assert.equal(typeof error, "object");
      const code = String((error as { code?: unknown }).code);
      assert.equal(
        code,
        dialect.name === "postgres"
          ? "21000"
          : dialect.name === "mysql"
            ? "ER_SUBQUERY_NO_1_ROW"
            : "305",
      );
      return true;
    });
    assert.equal(await base.select(({ p }) => ({ id: p.id })).count(), 3n);
    const oneNull = db
      .from({ c: definition })
      .where(({ c }) => c.id.eq(12))
      .select(({ c }) => ({ value: c.amount }))
      .scalar();
    const noRows = db
      .from({ c: definition })
      .where(({ c }) => c.id.eq(999))
      .select(({ c }) => ({ value: c.id }))
      .scalar();
    assert.deepEqual(
      await base
        .where(({ p }) => p.id.eq(1))
        .select(() => ({ oneNull, noRows }))
        .first(),
      { oneNull: null, noRows: null },
    );
    if (dialect.name !== "hana") {
      const first = base.select(({ p }) => ({
        id: p.id,
        first: db
          .from({ c: definition })
          .where(({ c }) => c.ownerId.eq(p.id))
          .orderBy(({ c }) => c.id.asc())
          .select(({ c }) => ({ id: c.id }))
          .limit(1)
          .scalar(),
      }));
      assert.deepEqual(await first.orderBy(({ p }) => p.id.asc()).execute(), [
        { id: 1, first: 10 },
        { id: 2, first: 12 },
        { id: 3, first: null },
      ]);
      const distinctChild = base.select(({ p }) => ({
        id: p.id,
        pastEnd: db
          .from({ c: definition })
          .where(({ c }) => c.ownerId.eq(p.id))
          .select(({ c }) => ({ owner: c.ownerId }))
          .distinct()
          .limit(1, 1)
          .exists(),
      }));
      assert.deepEqual(await distinctChild.orderBy(({ p }) => p.id.asc()).execute(), [
        { id: 1, pastEnd: false },
        { id: 2, pastEnd: false },
        { id: 3, pastEnd: false },
      ]);
    }
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
