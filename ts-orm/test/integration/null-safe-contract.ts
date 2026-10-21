import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import {
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

export async function verifyNullSafe(
  db: ReturnType<typeof createDatabase>,
  dialect: Dialect,
): Promise<void> {
  const name = "eqts_nullsafe_" + randomBytes(7).toString("hex");
  const money = types.decimal(19, 4).map({
    identity: Symbol("money"),
    to: (value: { amount: string }) => value.amount,
    from: (amount) => ({ amount }),
  });
  const definition = table(name, {
    id: column(types.integer()),
    left: column(types.integer()).nullable(),
    right: column(types.integer()).nullable(),
    flagLeft: column(types.boolean()).nullable(),
    flagRight: column(types.boolean()).nullable(),
    textLeft: column(types.text(120)).nullable(),
    textRight: column(types.text(120)).nullable(),
    moneyLeft: column(money).nullable(),
    moneyRight: column(money).nullable(),
  });
  const text = dialect.name === "hana" ? sql`NVARCHAR(120)` : sql`VARCHAR(120)`;
  let created = false;
  try {
    await db.command(sql`CREATE TABLE ${identifier(name)} (${identifier("id")} INTEGER PRIMARY KEY,
      ${identifier("left")} INTEGER, ${identifier("right")} INTEGER,
      ${identifier("flagLeft")} BOOLEAN, ${identifier("flagRight")} BOOLEAN,
      ${identifier("textLeft")} ${text}, ${identifier("textRight")} ${text},
      ${identifier("moneyLeft")} DECIMAL(19,4), ${identifier("moneyRight")} DECIMAL(19,4))`);
    created = true;
    const pairs = [
      [1, 1],
      [1, 2],
      [null, 1],
      [1, null],
      [null, null],
    ] as const;
    const word = (value: number | null) =>
      value === null ? null : value === 1 ? "O'Reilly 中文😀" : "different 中文😀";
    const flag = (value: number | null) => (value === null ? null : value === 2);
    await db
      .insert(definition)
      .values(
        pairs.map(([left, right], index) => ({
          id: index + 1,
          left,
          right,
          flagLeft: flag(left),
          flagRight: flag(right),
          textLeft: word(left),
          textRight: word(right),
          moneyLeft: left === null ? null : { amount: String(left) + ".0000" },
          moneyRight: right === null ? null : { amount: String(right) },
        })),
      )
      .execute();
    const base = db.from({ e: definition });
    const comparison = base
      .orderBy(({ e }) => e.id.asc())
      .select(({ e }) => ({
        equal: e.left.eqNullSafe(e.right),
        different: e.left.neNullSafe(e.right),
        negated: not(e.left.eqNullSafe(e.right)),
        ordinary: e.left.eq(e.right),
        boolean: e.flagLeft.eqNullSafe(e.flagRight),
        text: e.textLeft.eqNullSafe(e.textRight),
        money: e.moneyLeft.eqNullSafe(e.moneyRight),
      }));
    const results = await comparison.execute();
    results.forEach((row, index) => {
      const expected = index === 0 || index === 4;
      assert.deepEqual(row, {
        equal: expected,
        different: !expected,
        negated: !expected,
        ordinary: index < 2 ? expected : null,
        boolean: expected,
        text: expected,
        money: expected,
      });
    });
    assert.deepEqual(
      await base
        .where(({ e }) => e.left.eqNullSafe(e.right))
        .orderBy(({ e }) => e.id.asc())
        .select(({ e }) => ({ id: e.id }))
        .execute(),
      [{ id: 1 }, { id: 5 }],
    );
    assert.deepEqual(
      await base
        .where(({ e }) => not(e.left.eqNullSafe(e.right)))
        .orderBy(({ e }) => e.id.asc())
        .select(({ e }) => ({ id: e.id }))
        .execute(),
      [{ id: 2 }, { id: 3 }, { id: 4 }],
    );
    const joined = await base
      .leftJoin({ other: definition }, ({ e, other }) =>
        and(e.id.eq(other.id), e.left.eqNullSafe(other.right)),
      )
      .orderBy(({ e }) => e.id.asc())
      .select(({ e, other }) => ({ id: e.id, match: other.id }))
      .execute();
    assert.deepEqual(joined, [
      { id: 1, match: 1 },
      { id: 2, match: null },
      { id: 3, match: null },
      { id: 4, match: null },
      { id: 5, match: 5 },
    ]);
    const correlated = base
      .select(({ e }) => ({
        id: e.id,
        match: e.left.eqNullSafe(
          db
            .from({ c: definition })
            .where(({ c }) => c.id.eq(e.id))
            .select(({ c }) => ({ right: c.right }))
            .scalar(),
        ),
      }))
      .asCte("matched_rows");
    const matches = db
      .from({ r: correlated })
      .where(({ r }) => r.match)
      .orderBy(({ r }) => r.id.asc())
      .select(({ r }) => ({ id: r.id }));
    assert.equal(await matches.count(), 2n);
    assert.deepEqual((await matches.page(2, 1)).rows, [{ id: 5 }]);
    assert.equal(
      await base
        .where(({ e }) => e.moneyLeft.eqNullSafe({ amount: "1" }))
        .select(({ e }) => ({ id: e.id }))
        .count(),
      3n,
    );
  } finally {
    if (created) await db.command(sql`DROP TABLE ${identifier(name)}`);
  }
}
