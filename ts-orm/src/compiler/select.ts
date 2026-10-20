import type { Dialect } from "../dialects/dialect.js";
import { visitNode, type Node } from "../expression/node.js";
import { type QueryNode, type SelectNode } from "../query/model.js";
import { collectCtes } from "./cte.js";
import { Table } from "../schema/table.js";
import { types, type AnyDataType } from "../schema/data-type.js";

export interface CompiledQuery<T> {
  readonly sql: string;
  readonly parameters: readonly unknown[];
  decode(row: Readonly<Record<string, unknown>>): T;
}
type Scope = ReadonlyMap<symbol, string>;
interface State {
  dialect: Dialect;
  owner: symbol;
  parameters: unknown[];
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
    decode(row: Readonly<Record<string, unknown>>) {
      if (count) return types.bigint().decode(row["_eq_count"]) as T;
      const result: Record<string, unknown> = {};
      for (const slot of query.projection) {
        Object.defineProperty(result, slot.key, {
          value: decodeSlot(slot.type, row[slot.sqlAlias], slot.key),
          enumerable: true,
          writable: true,
          configurable: true,
        });
      }
      return result as T;
    },
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
function decodeSlot(type: AnyDataType, value: unknown, key: string): unknown {
  try {
    return type.decode(value);
  } catch (cause) {
    throw new TypeError(`Cannot decode projection "${key}" as ${type.storage.kind}`, { cause });
  }
}

function renderSelect(query: SelectNode, state: State): string {
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
  if (query.ordering.length)
    sql +=
      " ORDER BY " +
      query.ordering
        .map((order) => `${renderNode(order.expression, scope, state)} ${order.direction}`)
        .join(", ");
  return sql + state.dialect.pagination(query.limit, query.offset);
}

function requireRowExpression(node: Node, clause: string): void {
  visitNode(node, (current) => {
    if (current.kind === "call" && current.definition.phase !== "row")
      throw new TypeError(
        `${clause} cannot contain a same-level ${current.definition.phase}; project it into a derived query first`,
      );
  });
}
function isPredicate(node: Node): boolean {
  if (node.kind === "in" || node.kind === "unary" || node.kind === "constant") return true;
  if (node.kind === "binary") return !["+", "-", "*", "/"].includes(node.operator);
  return node.kind === "call" && node.definition.syntax === "predicate";
}

function renderNode(
  node: Node,
  scope: Scope,
  state: State,
  context: "value" | "predicate" = "value",
): string {
  if (state.dialect.name === "hana") {
    const predicate = isPredicate(node);
    if (context === "value" && predicate) {
      // 三值逻辑必须保留 UNKNOWN，不能把 CASE 的 ELSE 直接当作 FALSE。
      // 同一条件在最终 SQL 出现两次，两个位置分别渲染与绑定，不能复用 ? 文本。
      return `(CASE WHEN ${renderRawNode(node, scope, state)} THEN TRUE WHEN NOT ${renderRawNode(node, scope, state)} THEN FALSE ELSE NULL END)`;
    }
    if (context === "predicate" && !predicate)
      return `(${renderRawNode(node, scope, state)} = TRUE)`;
  }
  return renderRawNode(node, scope, state);
}

function renderRawNode(node: Node, scope: Scope, state: State): string {
  const { dialect } = state;
  switch (node.kind) {
    case "column": {
      const alias = scope.get(node.relation.id);
      if (node.relation.owner !== state.owner || alias === undefined)
        throw new TypeError("Column is outside this query scope");
      return dialect.quote(alias) + "." + dialect.quote(node.name);
    }
    case "parameter":
      state.parameters.push(node.value);
      return dialect.parameter(state.parameters.length, node.type.storage);
    case "constant":
      return node.value ? "(1 = 1)" : "(1 = 0)";
    case "identifier":
      return node.names.map((name) => dialect.quote(name)).join(".");
    case "fragment":
      return node.parts
        .map((part) => (typeof part === "string" ? part : renderNode(part, scope, state)))
        .join("");
    case "binary": {
      const context = node.operator === "AND" || node.operator === "OR" ? "predicate" : "value";
      return `(${renderNode(node.left, scope, state, context)} ${node.operator} ${renderNode(node.right, scope, state, context)})`;
    }
    case "unary":
      return node.operator === "NOT"
        ? `(NOT ${renderNode(node.operand, scope, state, "predicate")})`
        : `(${renderNode(node.operand, scope, state)} ${node.operator})`;
    case "in":
      return `(${renderNode(node.operand, scope, state)} ${node.negated ? "NOT IN" : "IN"} (${node.values.map((value) => renderNode(value, scope, state)).join(", ")}))`;
    case "call": {
      const renderer = node.definition.render[dialect.name];
      if (!renderer)
        throw new TypeError(`Function ${node.definition.name} does not support ${dialect.name}`);
      return renderRawNode(renderer(node.arguments), scope, state);
    }
  }
}
