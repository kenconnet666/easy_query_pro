import {
  aggregate,
  column,
  createDatabase,
  Expression,
  jsonNull,
  postgresDialect,
  postgresTypes,
  table,
  types,
  valueOf,
} from "@easy-query-pro/orm";

const money = types.decimal(19, 4).map({
  identity: Symbol("Money"),
  to: (value: { decimal: string }) => value.decimal,
  from: (decimal) => ({ decimal }),
});
const accounts = table("accounts", {
  id: column(types.integer()).generated(),
  name: column(types.text()),
  parentId: column(types.integer()).nullable(),
  balance: column(money),
});
const db = createDatabase({
  dialect: postgresDialect,
  extensions: {
    metrics: () => ({ twice: (value: Expression<number, "integer">) => value.add(value) }),
  },
});

// 保留固定的十层类型图，不用循环把 query 泛型提前拓宽成统一接口。
const graph = db
  .from({ a: accounts })
  .leftJoin({ p1: accounts }, ({ a, p1 }) => a.parentId.eq(p1.id))
  .leftJoin({ p2: accounts }, ({ p1, p2 }) => p1.parentId.eq(p2.id))
  .leftJoin({ p3: accounts }, ({ p2, p3 }) => p2.parentId.eq(p3.id))
  .leftJoin({ p4: accounts }, ({ p3, p4 }) => p3.parentId.eq(p4.id))
  .leftJoin({ p5: accounts }, ({ p4, p5 }) => p4.parentId.eq(p5.id))
  .leftJoin({ p6: accounts }, ({ p5, p6 }) => p5.parentId.eq(p6.id))
  .leftJoin({ p7: accounts }, ({ p6, p7 }) => p6.parentId.eq(p7.id))
  .leftJoin({ p8: accounts }, ({ p7, p8 }) => p7.parentId.eq(p8.id))
  .leftJoin({ p9: accounts }, ({ p8, p9 }) => p8.parentId.eq(p9.id))
  .leftJoin({ p10: accounts }, ({ p9, p10 }) => p9.parentId.eq(p10.id))
  .select(({ a, p10 }, api) => ({
    id: api.metrics.twice(a.id),
    name: a.name,
    ancestor: p10.name,
    balance: a.balance,
  }));
const one = graph.asCte("level_one");
const two = db
  .from({ r: one })
  .where(({ r }) => r.id.gt(0))
  .select(({ r }) => ({ id: r.id, name: r.name, ancestor: r.ancestor, balance: r.balance }))
  .asCte("level_two");
const three = db
  .from({ r: two })
  .select(({ r }) => ({ id: r.id, name: r.name, ancestor: r.ancestor, balance: r.balance }))
  .asCte("level_three");
const four = db
  .from({ r: three })
  .select(({ r }) => ({ id: r.id, name: r.name, ancestor: r.ancestor, balance: r.balance }))
  .asCte("level_four");
const query = db
  .from({ r: four })
  .orderBy(({ r }) => r.id.asc())
  .select(({ r }) => ({ id: r.id, name: r.name, ancestor: r.ancestor, balance: r.balance }));

type Row = Awaited<ReturnType<typeof query.execute>>[number];
function declarationsRemainSpecific(row: Row) {
  const statistic = aggregate.varianceSampleApprox(valueOf(1, types.integer()));
  // @ts-expect-error Published statistical aggregates must preserve the unavailable DISTINCT capability.
  statistic.filter(valueOf(true, types.boolean())).distinct();
  const id: number = row.id;
  const name: string = row.name;
  const ancestor: string | null = row.ancestor;
  const amount: { decimal: string } = row.balance;
  // @ts-expect-error Ten joins and four CTEs must not erase the last outer join's NULL.
  const wrong: string = row.ancestor;
  // @ts-expect-error Derived numeric outputs must not widen to any.
  const wrongId: string = row.id;
  // @ts-expect-error Business mappings survive the published declaration graph.
  const wrongAmount: string = row.balance;
  // @ts-expect-error A generated field cannot be inserted by a consumer.
  db.insert(accounts).values({ id: 1, name: "name", balance: { decimal: "1.00" } });
  // @ts-expect-error A native string carrier does not acquire text methods.
  valueOf("00000000-0000-0000-0000-000000000001", postgresTypes.uuid()).upper();
  // @ts-expect-error The namespace is local and exact, never a global any.
  db.extensions.missing;
  // @ts-expect-error An unknown JSON validator cannot erase the SQL NULL write boundary.
  types.json((value) => value).encode(null);
  // @ts-expect-error Scalar subqueries remain nullable in the published declarations.
  const requiredScalar: Expression<number, "integer"> = db
    .from({ x: accounts })
    .select(({ x }) => ({ id: x.id }))
    .scalar();
  // @ts-expect-error Package export maps keep internal source paths private.
  void import("@easy-query-pro/orm/src/query/query.js");
  void [id, name, ancestor, amount, wrong, wrongId, wrongAmount];
}
void declarationsRemainSpecific;

const compiled = query.compile();
if ((compiled.sql.match(/LEFT JOIN/g) ?? []).length !== 10 || !compiled.sql.startsWith("WITH "))
  throw new Error("The packed consumer lost its join or CTE graph");
if (compiled.parameters.length !== 1 || compiled.parameters[0] !== 0)
  throw new Error("The packed consumer lost its parameter binding");
const result = compiled.decode({
  _eq_0: "2",
  _eq_1: "name",
  _eq_2: null,
  _eq_3: "123456789012345.1234",
});
if (
  result.id !== 2 ||
  result.ancestor !== null ||
  result.balance.decimal !== "123456789012345.1234"
)
  throw new Error("The packed consumer lost its decoder or NULL contract");

const json = types.json((value) => value).nullable();
if (
  json.decode("null") !== jsonNull ||
  json.encode(jsonNull) !== "null" ||
  json.decode(null) !== null
)
  throw new Error("The packed consumer lost the JSON null boundary");
if (types.jsonText().decode('{"n":9007199254740993}') !== '{"n":9007199254740993}')
  throw new Error("The packed consumer changed raw JSON numeric text");

const scalar = db
  .from({ x: accounts })
  .select(({ x }) => ({ amount: x.balance }))
  .scalar();
const scalarQuery = db
  .from({ p: accounts })
  .select(() => ({ scalar }))
  .compile();
if (
  scalarQuery.decode({ _eq_0: null }).scalar !== null ||
  scalarQuery.decode({ _eq_0: "12.3400" }).scalar?.decimal !== "12.3400"
)
  throw new Error("The packed consumer lost its scalar subquery decoder");

const average = db
  .from({ a: accounts })
  .select(({ a }) => ({ mean: aggregate.avgApprox(a.balance) }))
  .compile();
if (
  average.decode({ _eq_0: "1.25" }).mean !== 1.25 ||
  average.decode({ _eq_0: null }).mean !== null
)
  throw new Error("The packed consumer lost its approximate average domain");

const dispersion = db
  .from({ a: accounts })
  .select(({ a }) => ({ variance: aggregate.variancePopulationApprox(a.balance) }))
  .compile();
if (dispersion.decode({ _eq_0: "2.5e-9" }).variance !== 2.5e-9)
  throw new Error("The packed consumer lost the dispersion result domain");
