import {
  column,
  createDatabase,
  nativeCast,
  postgresDialect,
  postgresTypes,
  table,
  types,
  valueOf,
} from "../../src/index.js";

const ids = table("identities", { id: column(postgresTypes.uuid()), text: column(types.text()) });
const db = createDatabase({ dialect: postgresDialect });
const base = db.from({ i: ids });
// @ts-expect-error A string-backed UUID is not a text expression.
base.scope.i.id.upper();
// @ts-expect-error UUIDs do not participate in arithmetic.
base.scope.i.id.add("1");
// @ts-expect-error Comparing text and UUID requires an explicit database conversion.
base.scope.i.id.eq(base.scope.i.text);
const cast = nativeCast(base.scope.i.text, postgresTypes.uuid());
const result: string | null = cast.type.decode(null);
// @ts-expect-error Database CAST can produce NULL even with a non-null source.
const nonNull: string = cast.type.decode(null);
const selected = base.select(({ i }) => ({ id: i.id }));
const different = base.select(({ i }) => ({ id: i.text }));
// @ts-expect-error Equal JavaScript carriers do not make SQL storage compatible.
selected.union(different);
const optional = base
  .leftJoin({ p: ids }, ({ i, p }) => i.id.eq(p.id))
  .select(({ p }) => ({ id: p.id }));
const combined = db.from({ x: selected.unionAll(optional) }).select(({ x }) => ({ id: x.id }));
declare const row: Awaited<ReturnType<typeof combined.execute>>[number];
const nullable: string | null = row.id;
const binding: string = valueOf(
  "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
  postgresTypes.uuid(),
).type.decode("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
void [result, nullable, binding];
