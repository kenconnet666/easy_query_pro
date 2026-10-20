import {
  column,
  Column,
  createDatabase,
  postgresDialect,
  table,
  types,
  type InsertOf,
  type UpdateOf,
} from "../../src/index.js";

const records = table("records", {
  id: column(types.integer()).generated().nullable().defaulted().named("record_id"),
  name: column(types.text()),
  note: column(types.text()).nullable(),
  counter: column(types.integer()).defaulted(),
});
const db = createDatabase({ dialect: postgresDialect });
db.insert(records).values({ name: "a", note: undefined });
db.update(records)
  .set((fields) => ({ counter: fields.counter.add(1), note: null }))
  .where((fields) => fields.name.eq("a"));
const inputWithId = { name: "a", id: 1 };
// @ts-expect-error Read-only fields remain forbidden even in a named variable and after column modifiers.
db.insert(records).values(inputWithId);
// @ts-expect-error A required field must be provided.
db.insert(records).values({ note: null });
// @ts-expect-error Required values cannot be undefined.
db.insert(records).values({ name: undefined });
// @ts-expect-error The named field is not nullable.
db.insert(records).values({ name: null });
// @ts-expect-error Generated columns cannot be changed by UPDATE.
db.update(records).set({ id: 2 });
// @ts-expect-error Value SQL families are preserved.
db.update(records).set((fields) => ({ counter: fields.name }));
// @ts-expect-error DELETE does not assign values.
db.deleteFrom(records).set({ name: "a" });
// @ts-expect-error INSERT VALUES cannot use a source WHERE predicate.
db.insert(records).where((fields) => fields.name.eq("a"));
const returned = db
  .insert(records)
  .values({ name: "a" })
  .returning((fields) => ({ label: fields.name, id: fields.id }));
declare const result: Awaited<ReturnType<typeof returned.execute>>;
const label: string = result.rows[0]!.label;
const id: number | null = result.rows[0]!.id;
// @ts-expect-error A nullable generated column remains nullable when returned.
const wrong: number = result.rows[0]!.id;
const manual = table("manual", {
  id: new Column(types.integer(), { generated: true }),
  name: column(types.text()),
});
// @ts-expect-error Direct constructor options also preserve read-only metadata in their type.
const insert: InsertOf<typeof manual.columns> = { id: 1, name: "a" };
// @ts-expect-error UpdateOf follows the same source metadata.
const update: UpdateOf<typeof records.columns> = { id: 1 };
void [label, id];
// @ts-expect-error Explicit metadata types cannot omit their corresponding runtime options.
new Column<number, "integer", { hasDefault: true }>(types.integer());
// @ts-expect-error A default empty metadata type cannot hide a supplied generated flag.
new Column<number, "integer">(types.integer(), { generated: true });
