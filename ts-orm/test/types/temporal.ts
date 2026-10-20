import {
  aggregate,
  column,
  createDatabase,
  nativeCast,
  postgresDialect,
  table,
  types,
  valueOf,
} from "../../src/index.js";

const db = createDatabase({ dialect: postgresDialect });
const query = db.from({
  e: table("events", {
    day: column(types.date()),
    occurred: column(types.localDateTime()).nullable(),
  }),
});
const selected = query.select(({ e }) => ({
  first: aggregate.min(e.occurred),
  last: aggregate.max(e.day),
}));
declare const row: Awaited<ReturnType<typeof selected.execute>>[number];
const expected: { first: string | null; last: string | null } = row;
// @ts-expect-error DATE does not implicitly use text functions.
query.scope.e.day.upper();
// @ts-expect-error DATE and local date-time are distinct SQL families.
query.scope.e.day.eq(query.scope.e.occurred);
// @ts-expect-error Temporal parameters preserve local fields, not JS Date instants.
valueOf(new Date(), types.localDateTime());
const cast = nativeCast(query.scope.e.occurred, types.date());
const nullable: string | null = cast.type.decode(null);
void [expected, nullable];
