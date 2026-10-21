import {
  column,
  createDatabase,
  not,
  postgresDialect,
  table,
  types,
  valueOf,
  type Expression,
} from "../../src/index.js";
const entries = table("entries", {
  id: column(types.integer()),
  optional: column(types.integer()).nullable(),
});
const db = createDatabase({ dialect: postgresDialect });
const base = db.from({ e: entries });
const same: Expression<boolean, "boolean"> = base.scope.e.id.eqNullSafe(base.scope.e.optional);
const different: Expression<boolean, "boolean"> = base.scope.e.optional.neNullSafe(null);
const negated: Expression<boolean, "boolean"> = not(same);
const nullable: Expression<boolean | null, "boolean"> = not(base.scope.e.optional.eq(1));
// @ts-expect-error Ordinary equality still has SQL UNKNOWN.
const required: Expression<boolean, "boolean"> = not(base.scope.e.optional.eq(1));
// @ts-expect-error The compared JavaScript value must match the field's codec.
base.scope.e.id.eqNullSafe("1");
// @ts-expect-error Equal JavaScript carriers do not make different SQL kinds interchangeable.
base.scope.e.id.neNullSafe(valueOf(1, types.float64()));
// @ts-expect-error Undefined must remain distinct from SQL NULL in declaration files.
base.scope.e.optional.eqNullSafe(undefined);
// @ts-expect-error NOT only accepts a boolean expression.
not(base.scope.e.id);
const report = base.select(() => ({ same, different, negated, nullable }));
declare const row: Awaited<ReturnType<typeof report.execute>>[number];
const plain: boolean = row.negated;
const unknown: boolean | null = row.nullable;
void [plain, unknown];
