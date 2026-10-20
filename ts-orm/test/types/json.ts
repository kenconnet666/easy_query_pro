import {
  column,
  createDatabase,
  jsonNull,
  postgresDialect,
  table,
  types,
  type JsonNull,
  type RowOf,
} from "../../src/index.js";

function document(value: unknown): { count: number; nested: null } | null {
  if (value === null) return null;
  if (
    typeof value === "object" &&
    value !== null &&
    "count" in value &&
    typeof value.count === "number"
  )
    return { count: value.count, nested: null };
  throw new TypeError("Expected a count document");
}
const json = types.json(document);
const unvalidated = types.json((value) => value);
// @ts-expect-error An unknown validator still cannot write SQL NULL without nullable().
unvalidated.encode(null);
// @ts-expect-error Undefined is not a JSON document.
unvalidated.encode(undefined);
json.encode(jsonNull);
json.encode({ count: 1, nested: null });
// @ts-expect-error JSON null has an explicit sentinel; SQL NULL requires nullable().
json.encode(null);
// @ts-expect-error The validator's output shape remains the write contract.
json.encode({ count: "1", nested: null });
// @ts-expect-error A JSON number's carrier does not turn a JSON column into an integer.
types.jsonText().encode(1);
const entries = table("json_entries", {
  id: column(types.integer()),
  document: column(json).nullable(),
  raw: column(types.jsonText()),
});
declare const row: RowOf<typeof entries.columns>;
const value: { count: number; nested: null } | JsonNull | null = row.document;
const raw: string = row.raw;
const db = createDatabase({ dialect: postgresDialect });
db.insert(entries).values({ id: 1, document: jsonNull, raw: "null" });
const report = db
  .from({ e: entries })
  .select(({ e }) => ({ document: e.document, raw: e.raw }))
  .asCte("json_docs");
const result = db.from({ d: report }).select(({ d }) => ({ document: d.document, raw: d.raw }));
declare const output: Awaited<ReturnType<typeof result.execute>>[number];
const documentOutput: typeof value = output.document;
// @ts-expect-error JSON text is not a text SQL column and has no UPPER operation.
result.scope.d.raw.upper();
void [value, raw, documentOutput];
