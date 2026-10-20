import {
  aggregate,
  column,
  createDatabase,
  frame,
  postgresDialect,
  table,
  types,
  windowFunctions,
  windowSpec,
} from "../../src/index.js";
const db = createDatabase({ dialect: postgresDialect });
const events = table("events", {
  id: column(types.integer()),
  value: column(types.integer()).nullable(),
  label: column(types.text()),
});
const base = db.from({ e: events });
const ordered = windowSpec().orderBy(base.scope.e.id.asc());
const selected = base.select(({ e }) => ({
  row: windowFunctions.rowNumber().over(ordered),
  lag: windowFunctions.lag(e.value, 1, 999).over(ordered),
  total: aggregate
    .sum(e.value)
    .over(ordered.rowsBetween(frame.unboundedPreceding, frame.currentRow)),
  count: aggregate.count().over(ordered),
}));
declare const row: Awaited<ReturnType<typeof selected.execute>>[number];
const shape: { row: bigint; lag: number | null; total: string | null; count: bigint } = row;
// @ts-expect-error Lag remains nullable when an existing row contains SQL NULL.
const nonNullLag: number = row.lag;
// @ts-expect-error A bare window function needs OVER before becoming a SQL expression.
base.select(() => ({ bad: windowFunctions.rowNumber() }));
// @ts-expect-error The frame start cannot be UNBOUNDED FOLLOWING.
ordered.rowsBetween(frame.unboundedFollowing, frame.currentRow);
// @ts-expect-error The frame end cannot be UNBOUNDED PRECEDING.
ordered.rowsBetween(frame.currentRow, frame.unboundedPreceding);
// @ts-expect-error The default must match the source value and SQL family.
windowFunctions.lag(base.scope.e.value, 1, base.scope.e.label);
// @ts-expect-error Only a bare aggregate can receive OVER, not an arbitrary arithmetic wrapper.
aggregate.sum(base.scope.e.value).add("1").over(ordered);
// @ts-expect-error NULL placement has a closed set of supported values.
base.scope.e.id.asc("middle");
void shape;
