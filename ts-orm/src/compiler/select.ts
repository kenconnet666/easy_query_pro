import type { Dialect } from "../dialects/dialect.js";
import { visitNode, type Node } from "../expression/node.js";
import { DerivedSource, type ProjectionSlot, type SelectNode } from "../query/model.js";
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
  query: SelectNode,
  dialect: Dialect,
  count = false,
): CompiledQuery<T> {
  const state: State = { dialect, owner: query.owner, parameters: [] };
  const inner = renderSelect(query, state);
  const sql = count
    ? `SELECT COUNT(*) AS ${dialect.quote("_eq_count")} FROM (${inner}) ${dialect.quote("_eq_report")}`
    : inner;
  return Object.freeze({
    sql,
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
        : `(${renderSelect((source as DerivedSource<never>).query, state)})`;
    if (index > 0) sql += relation.join === "CROSS" ? " CROSS JOIN " : ` ${relation.join} JOIN `;
    sql += `${sourceSql} ${quote(relation.alias)}`;
    if (relation.on) {
      requireRowExpression(relation.on, "JOIN");
      sql += ` ON ${renderNode(relation.on, visible, state)}`;
    }
  }
  if (query.where) {
    requireRowExpression(query.where, "WHERE");
    sql += ` WHERE ${renderNode(query.where, scope, state)}`;
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
function renderNode(node: Node, scope: Scope, state: State): string {
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
    case "binary":
      return `(${renderNode(node.left, scope, state)} ${node.operator} ${renderNode(node.right, scope, state)})`;
    case "unary":
      return node.operator === "NOT"
        ? `(NOT ${renderNode(node.operand, scope, state)})`
        : `(${renderNode(node.operand, scope, state)} ${node.operator})`;
    case "in":
      return `(${renderNode(node.operand, scope, state)} ${node.negated ? "NOT IN" : "IN"} (${node.values.map((value) => renderNode(value, scope, state)).join(", ")}))`;
    case "call": {
      const renderer = node.definition.render[dialect.name];
      if (!renderer)
        throw new TypeError(`Function ${node.definition.name} does not support ${dialect.name}`);
      return renderNode(renderer(node.arguments), scope, state);
    }
  }
}
