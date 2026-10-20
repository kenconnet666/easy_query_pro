import {
  column,
  createDatabase,
  pgArray,
  postgresDialect,
  postgresTypes,
  table,
  types,
  valueOf,
} from "../../src/index.js";

const numbers = postgresTypes.array(types.integer().nullable());
const db = createDatabase({ dialect: postgresDialect });
const source = db.from({
  a: table("arrays", { numbers: column(numbers), maybe: column(numbers).nullable() }),
});
const array = source.scope.a.numbers.mount(pgArray);
const count: number = array.cardinality().type.decode(2);
const maybe: number | null = source.scope.a.maybe.mount(pgArray).cardinality().type.decode(null);
const chosen = source.select(({ a }) => ({ values: a.numbers, maybe: a.maybe }));
declare const row: Awaited<ReturnType<typeof chosen.execute>>[number];
const expected: { values: readonly (number | null)[]; maybe: readonly (number | null)[] | null } =
  row;
// @ts-expect-error Scalar TEXT and SQL ARRAY have different expression families.
source.scope.a.numbers.upper();
// @ts-expect-error Arrays retain their element SQL family.
array.contains(valueOf(["x"], postgresTypes.array(types.text())));
// @ts-expect-error ANY equality takes a non-null business value.
array.anyEquals(null);
// @ts-expect-error The first ANY API does not accept scalar expressions.
array.anyEquals(source.scope.a.numbers);
const uuids = valueOf(
  ["00000000-0000-0000-0000-000000000001"],
  postgresTypes.array(postgresTypes.uuid()),
).mount(pgArray);
// @ts-expect-error UUID[] and TEXT[] differ even though both use strings.
uuids.contains(valueOf(["text"], postgresTypes.array(types.text())));
// @ts-expect-error JSON null requires a separate contract before JSON array support.
postgresTypes.array(types.json((value) => value));
const derived = db
  .from({ a: chosen.asCte("arrays_cte") })
  .select(({ a }) => ({ result: a.values.mount(pgArray).overlaps([1]) }));
void [count, maybe, expected, derived];
