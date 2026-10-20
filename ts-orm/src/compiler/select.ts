import type { Dialect } from "../dialects/dialect.js";
import { visitNode, type Node } from "../expression/node.js";
import {
  renderNode,
  createState,
  renderOrders,
  requireRowExpression,
  requireNodeOperation,
  type State,
  type Scope,
} from "./expression.js";
import {
  type DerivedSource,
  type Selection,
  type QueryNode,
  type SelectNode,
} from "../query/model.js";
import { expressionColumns, freeQueryColumns, queryExpressions } from "../query/analysis.js";
import { projectionDecoder } from "./projection.js";
import { collectCtes } from "./cte.js";
import { validateGrouping } from "./grouping.js";
import { Table } from "../schema/table.js";
import { types } from "../schema/data-type.js";
import { requireNativeOperation, requireNativeSupport } from "../schema/native-type.js";

export interface CompiledQuery<T> {
  readonly sql: string;
  readonly parameters: readonly unknown[];
  decode(row: Readonly<Record<string, unknown>>): T;
}
export function compileCommand(
  node: Node,
  dialect: Dialect,
  owner: symbol,
): { sql: string; parameters: readonly unknown[] } {
  const state = createState(dialect, owner);
  const sql = renderNode(node, new Map(), state);
  return Object.freeze({ sql, parameters: Object.freeze(state.parameters) });
}

/** 一次遍历产生 SQL、参数和投影计划，重复引用按实际渲染位置绑定，不扫描 SQL 占位符。 */
export function compileSelect<T>(
  query: QueryNode,
  dialect: Dialect,
  count = false,
): CompiledQuery<T> {
  const state = createState(dialect, query.owner);
  const definitions = collectCtes(query);
  const declared = new Set(definitions);
  state.renderSubquery = (child, scope) => renderQuery(child, state, declared, scope);
  const withSql = definitions.length
    ? "WITH " +
      definitions
        .map(
          (source) =>
            `${dialect.quote(source.cteName!)} AS (${renderQuery(source.query, state, declared)})`,
        )
        .join(", ") +
      " "
    : "";
  const inner = renderQuery(query, state, declared);
  const body = count
    ? `SELECT COUNT(*) AS ${dialect.quote("_eq_count")} FROM (${inner}) ${dialect.quote("_eq_report")}`
    : inner;
  return Object.freeze({
    sql: withSql + body,
    parameters: Object.freeze(state.parameters),
    decode: count
      ? (row) => types.bigint().decode(row["_eq_count"]) as T
      : projectionDecoder<T>(query.projection),
  });
}

type CteSet = ReadonlySet<DerivedSource<Selection>>;

function renderQuery(
  query: QueryNode,
  state: State,
  declared: CteSet,
  ancestors: Scope = new Map(),
): string {
  if (query.owner !== state.owner) throw new TypeError("A source cannot cross database clients");
  if (query.kind === "select") return renderSelect(query, state, declared, ancestors);
  if (!query.all)
    for (const slot of query.projection)
      requireNativeOperation(slot.type.storage, state.dialect.name, "equality");
  if (
    !query.all &&
    state.dialect.name === "hana" &&
    query.projection.some((slot) => slot.type.storage.kind === "json")
  )
    throw new TypeError(
      "HANA cannot deduplicate NCLOB JSON values in UNION; use a comparable projection",
    );
  const columns = query.projection.map((slot) => state.dialect.quote(slot.sqlAlias)).join(", ");
  // 分支包装保留各自 ORDER/LIMIT，也使三库不依赖裸括号 SELECT 的集合运算语法差异。
  return (
    `SELECT ${columns} FROM (${renderQuery(query.left, state, declared)}) ${state.dialect.quote("_eq_set_left")} UNION${query.all ? " ALL" : ""} ` +
    `SELECT ${columns} FROM (${renderQuery(query.right, state, declared)}) ${state.dialect.quote("_eq_set_right")}`
  );
}
function renderSelect(query: SelectNode, state: State, declared: CteSet, ancestors: Scope): string {
  validateGrouping(query);
  if (query.owner !== state.owner) throw new TypeError("A source cannot cross database clients");
  if (query.projection.length === 0)
    throw new TypeError("Select explicit output columns before compiling or executing");
  const captured = freeQueryColumns(query);
  if (captured.some((column) => !ancestors.has(column.relation.id)))
    throw new TypeError("Column is outside this query scope");
  if (
    captured.length &&
    state.dialect.name === "hana" &&
    (query.ordering.length || query.limit !== undefined || query.offset)
  )
    throw new TypeError("HANA correlated subqueries with ordering or pagination are not supported");
  const local = new Set(query.relations.map((relation) => relation.id));
  for (const expression of queryExpressions(query))
    visitNode(expression, (node) => {
      if (
        node.kind === "aggregate" ||
        node.kind === "window" ||
        (node.kind === "call" && node.definition.phase !== "row")
      ) {
        if (expressionColumns(node).some((column) => !local.has(column.relation.id)))
          throw new TypeError(
            "Aggregate and window inputs cannot capture outer columns; project a relation boundary first",
          );
        return false;
      }
    });
  const scope = relationScope(query, ancestors);
  if (query.groupingMode === "rollup") state.groupingScopes.set(scope, query.grouping);
  const quote = state.dialect.quote;
  for (const slot of query.projection) {
    requireNativeSupport(slot.type.storage, state.dialect.name);
    if (query.distinct) requireNativeOperation(slot.type.storage, state.dialect.name, "equality");
  }
  for (const key of query.grouping) requireNodeOperation(key, state.dialect, "equality");
  // SQL 文本出现的顺序就是参数绑定顺序：SELECT、FROM/子查询、JOIN、WHERE、ORDER。
  const selected = query.projection
    .map((slot) => `${renderNode(slot.node, scope, state)} AS ${quote(slot.sqlAlias)}`)
    .join(", ");
  let sql = `SELECT ${query.distinct ? "DISTINCT " : ""}${selected} FROM `;
  const visible = new Map(ancestors);
  for (let index = 0; index < query.relations.length; index++) {
    const relation = query.relations[index]!;
    const alias = scope.get(relation.id)!;
    visible.set(relation.id, alias);
    const source = relation.source;
    if (!(source instanceof Table) && source.cteName !== undefined && !declared.has(source))
      throw new TypeError(
        "A CTE must be present in the query graph, not introduced only by an extension renderer",
      );
    const sourceSql =
      source instanceof Table
        ? (source.schema === undefined ? "" : quote(source.schema) + ".") + quote(source.name)
        : source.cteName !== undefined
          ? quote(source.cteName)
          : `(${renderQuery(source.query, state, declared)})`;
    if (index > 0) sql += relation.join === "CROSS" ? " CROSS JOIN " : ` ${relation.join} JOIN `;
    sql += `${sourceSql} ${quote(alias)}`;
    if (relation.on) {
      requireRowExpression(relation.on, "JOIN");
      sql += ` ON ${renderNode(relation.on, visible, state, "predicate")}`;
    }
  }
  if (query.where) {
    requireRowExpression(query.where, "WHERE");
    sql += ` WHERE ${renderNode(query.where, scope, state, "predicate")}`;
  }
  if (query.grouping.length) {
    const keys = query.grouping.map((key) => renderNode(key, scope, state)).join(", ");
    sql +=
      " GROUP BY " +
      (query.groupingMode === "rollup"
        ? state.dialect.name === "mysql"
          ? keys + " WITH ROLLUP"
          : `ROLLUP (${keys})`
        : keys);
  }
  const omitEmptyTotal = query.groupingMode === "rollup" && state.dialect.name === "postgres";
  if (query.having || omitEmptyTotal) {
    const having = query.having ? renderNode(query.having, scope, state, "predicate") : undefined;
    sql +=
      " HAVING " + (omitEmptyTotal ? (having ? `(${having}) AND ` : "") + "COUNT(*) > 0" : having);
  }
  if (query.ordering.length) sql += " ORDER BY " + renderOrders(query.ordering, scope, state);
  return sql + state.dialect.pagination(query.limit, query.offset);
}

/** SQL alias 是编译细节，公共 scope 键和 relation identity 不变；避免内层同名来源遮住外层引用。 */
function relationScope(query: SelectNode, ancestors: Scope): Scope {
  const scope = new Map(ancestors);
  const used = new Set([...ancestors.values()].map((alias) => alias.toLowerCase()));
  let next = 1;
  for (const relation of query.relations) {
    if (ancestors.has(relation.id))
      throw new TypeError(
        "A subquery must bind fresh relation identities; start it with database.from()",
      );
    let alias = relation.alias;
    if (used.has(alias.toLowerCase())) {
      do {
        alias = "_eq_subquery_" + next++;
      } while (used.has(alias.toLowerCase()));
    }
    used.add(alias.toLowerCase());
    scope.set(relation.id, alias);
  }
  return scope;
}
