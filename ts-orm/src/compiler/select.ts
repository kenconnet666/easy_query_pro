import type { Dialect } from "../dialects/dialect.js";
import type { Node } from "../expression/node.js";
import { renderNode, requireRowExpression, type State } from "./expression.js";
import { type QueryNode, type SelectNode } from "../query/model.js";
import { projectionDecoder } from "./projection.js";
import { collectCtes } from "./cte.js";
import { validateGrouping } from "./grouping.js";
import { Table } from "../schema/table.js";
import { types } from "../schema/data-type.js";

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
  const state: State = { dialect, owner, parameters: [] };
  const sql = renderNode(node, new Map(), state);
  return Object.freeze({ sql, parameters: Object.freeze(state.parameters) });
}

/** 一次遍历产生 SQL、参数和投影计划，重复引用按实际渲染位置绑定，不扫描 SQL 占位符。 */
export function compileSelect<T>(
  query: QueryNode,
  dialect: Dialect,
  count = false,
): CompiledQuery<T> {
  const state: State = { dialect, owner: query.owner, parameters: [] };
  const definitions = collectCtes(query);
  const withSql = definitions.length
    ? "WITH " +
      definitions
        .map(
          (source) => `${dialect.quote(source.cteName!)} AS (${renderQuery(source.query, state)})`,
        )
        .join(", ") +
      " "
    : "";
  const inner = renderQuery(query, state);
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

function renderQuery(query: QueryNode, state: State): string {
  if (query.kind === "select") return renderSelect(query, state);
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
    `SELECT ${columns} FROM (${renderQuery(query.left, state)}) ${state.dialect.quote("_eq_set_left")} UNION${query.all ? " ALL" : ""} ` +
    `SELECT ${columns} FROM (${renderQuery(query.right, state)}) ${state.dialect.quote("_eq_set_right")}`
  );
}
function renderSelect(query: SelectNode, state: State): string {
  validateGrouping(query);
  if (query.owner !== state.owner) throw new TypeError("A source cannot cross database clients");
  if (query.projection.length === 0)
    throw new TypeError("Select explicit output columns before compiling or executing");
  const scope = new Map(query.relations.map((relation) => [relation.id, relation.alias]));
  const quote = state.dialect.quote;
  // SQL 文本出现的顺序就是参数绑定顺序：SELECT、FROM/子查询、JOIN、WHERE、ORDER。
  const selected = query.projection
    .map((slot) => `${renderNode(slot.node, scope, state)} AS ${quote(slot.sqlAlias)}`)
    .join(", ");
  let sql = `SELECT ${query.distinct ? "DISTINCT " : ""}${selected} FROM `;
  const visible = new Map<symbol, string>();
  for (let index = 0; index < query.relations.length; index++) {
    const relation = query.relations[index]!;
    visible.set(relation.id, relation.alias);
    const source = relation.source;
    const sourceSql =
      source instanceof Table
        ? (source.schema === undefined ? "" : quote(source.schema) + ".") + quote(source.name)
        : source.cteName !== undefined
          ? quote(source.cteName)
          : `(${renderQuery(source.query, state)})`;
    if (index > 0) sql += relation.join === "CROSS" ? " CROSS JOIN " : ` ${relation.join} JOIN `;
    sql += `${sourceSql} ${quote(relation.alias)}`;
    if (relation.on) {
      requireRowExpression(relation.on, "JOIN");
      sql += ` ON ${renderNode(relation.on, visible, state, "predicate")}`;
    }
  }
  if (query.where) {
    requireRowExpression(query.where, "WHERE");
    sql += ` WHERE ${renderNode(query.where, scope, state, "predicate")}`;
  }
  if (query.grouping.length)
    sql += " GROUP BY " + query.grouping.map((key) => renderNode(key, scope, state)).join(", ");
  if (query.having) sql += " HAVING " + renderNode(query.having, scope, state, "predicate");
  if (query.ordering.length)
    sql +=
      " ORDER BY " +
      query.ordering
        .map((order) => `${renderNode(order.expression, scope, state)} ${order.direction}`)
        .join(", ");
  return sql + state.dialect.pagination(query.limit, query.offset);
}
