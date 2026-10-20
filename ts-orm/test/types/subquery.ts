import {
  aggregate,
  column,
  createDatabase,
  postgresDialect,
  table,
  types,
  type Expression,
} from "../../src/index.js";
const users = table("users", {
  id: column(types.integer()),
  parentId: column(types.integer()).nullable(),
});
const db = createDatabase({
  dialect: postgresDialect,
  extensions: { extra: () => ({ tag: "kept" as const }) },
});
const base = db.from({ u: users });
const exists: Expression<boolean, "boolean"> = db
  .from({ c: users })
  .where(({ c }) => c.parentId.eq(base.scope.u.id))
  .select(({ c }) => ({ id: c.id }))
  .exists();
const scalar: Expression<number | null, "integer"> = db
  .from({ c: users })
  .select(({ c }) => ({ id: c.id }))
  .scalar();
const count: Expression<bigint | null, "bigint"> = db
  .from({ c: users })
  .select(() => ({ count: aggregate.count() }))
  .scalar();
// @ts-expect-error Zero rows make even a non-null selected field nullable.
const required: Expression<number, "integer"> = db
  .from({ c: users })
  .select(({ c }) => ({ id: c.id }))
  .scalar();
// @ts-expect-error Select a single explicit field before using a scalar expression.
base.scalar();
// @ts-expect-error EXISTS preserves an explicit selected query.
base.exists();
// @ts-expect-error Multiple fields cannot be used as a scalar.
base.select(({ u }) => ({ id: u.id, parent: u.parentId })).scalar();
const report = base.select(({ u }, api) => ({ id: u.id, exists, scalar, count }));
declare const row: Awaited<ReturnType<typeof report.execute>>[number];
const has: boolean = row.exists;
const id: number | null = row.scalar;
const total: bigint | null = row.count;
void [has, id, total];
