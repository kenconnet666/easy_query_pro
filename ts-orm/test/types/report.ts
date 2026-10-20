import {
  aggregate,
  caseWhen,
  coalesce,
  column,
  createDatabase,
  nativeCast,
  nullIf,
  postgresDialect,
  table,
  types,
  valueOf,
} from "../../src/index.js";
const sales = table("sales", {
  team: column(types.text()).nullable(),
  amount: column(types.decimal(19, 4)).nullable(),
  units: column(types.integer()),
});
const db = createDatabase({ dialect: postgresDialect });
const grouped = db.from({ s: sales }).groupBy(({ s }) => ({ team: s.team }));
const selected = grouped
  .where(({ s }) => s.units.gt(0))
  .having(({ s }, _e, g) => aggregate.count(s.amount).gt(0n))
  .orderBy((_s, _e, g) => g.team.asc())
  .select(({ s }, _e, g) => ({
    team: g.team,
    total: aggregate.sum(s.amount),
    count: aggregate.count(),
    min: aggregate.min(s.units),
  }));
declare const row: Awaited<ReturnType<typeof selected.execute>>[number];
const expected: { team: string | null; total: string | null; count: bigint; min: number | null } =
  row;
// @ts-expect-error A grouped count uses bigint rather than an unsafe number promise.
const count: number = row.count;
// @ts-expect-error SUM must remain nullable for an empty/all-NULL aggregate input.
const total: string = row.total;
// @ts-expect-error Named group keys are preserved exactly through the query chain.
grouped.limit(1).select((_s, _e, g) => ({ missing: g.noSuchKey }));
// @ts-expect-error SUM accepts numeric SQL families only.
aggregate.sum(grouped.scope.s.team);
// @ts-expect-error Aggregate filters must be boolean expressions.
aggregate.count().filter(grouped.scope.s.units);
const filled = coalesce(aggregate.sum(grouped.scope.s.amount), valueOf("0", types.decimal(38, 4)));
const filledValue: string = filled.type.decode("0.0000");
const choice = caseWhen(
  grouped.scope.s.units.gt(0),
  valueOf("yes", types.text()),
  valueOf(null, types.text().nullable()),
);
const maybe: string | null = choice.type.decode(null);
// @ts-expect-error CASE branches must use the same SQL family.
caseWhen(grouped.scope.s.units.gt(0), grouped.scope.s.team, grouped.scope.s.units);
// @ts-expect-error COALESCE does not turn text and decimal into the same family because both use string.
coalesce(grouped.scope.s.team, grouped.scope.s.amount);
const nullableNative = nativeCast(grouped.scope.s.amount, types.integer());
const nativeValue: number | null = nullableNative.type.decode(null);
const nulled = nullIf(grouped.scope.s.units, valueOf(0, types.integer()));
const nullValue: number | null = nulled.type.decode(null);
const other = db
  .from({ s: sales })
  .groupBy(({ s }) => ({ team: s.team }))
  .select(({ s }, _e, g) => ({
    team: g.team,
    total: aggregate.sum(s.amount),
    count: aggregate.count(),
    min: aggregate.min(s.units),
  }));
db.from({ combined: selected.unionAll(other) }).select(({ combined }) => ({
  total: combined.total,
}));
void [expected, filledValue, maybe, nativeValue, nullValue];
