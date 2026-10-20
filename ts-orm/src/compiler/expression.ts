import type { Dialect } from "../dialects/dialect.js";
import { visitNode, type Node } from "../expression/node.js";

export type Scope = ReadonlyMap<symbol, string>;
export interface State {
  dialect: Dialect;
  owner: symbol;
  parameters: unknown[];
}

export function requireRowExpression(node: Node, clause: string): void {
  visitNode(node, (current) => {
    if (current.kind === "aggregate")
      throw new TypeError(
        `${clause} cannot contain a same-level aggregate; project it into a derived query first`,
      );
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

export function renderNode(
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
      return (alias === "" ? "" : dialect.quote(alias) + ".") + dialect.quote(node.name);
    }
    case "parameter":
      state.parameters.push(node.value);
      return dialect.parameter(state.parameters.length, node.type.storage);
    case "cast":
      return dialect.cast(renderNode(node.operand, scope, state), node.type.storage);
    case "case":
      return `(CASE WHEN ${renderNode(node.condition, scope, state, "predicate")} THEN ${renderNode(node.whenTrue, scope, state)} ELSE ${renderNode(node.whenFalse, scope, state)} END)`;
    case "coalesce":
    case "nullif":
      return `${node.kind.toUpperCase()}(${renderNode(node.left, scope, state)}, ${renderNode(node.right, scope, state)})`;
    case "aggregate": {
      if (node.operand) requireRowExpression(node.operand, "Aggregate operand");
      if (node.filter) requireRowExpression(node.filter, "Aggregate filter");
      if (node.distinct && dialect.name === "hana" && node.operandType?.storage.kind === "json")
        throw new TypeError("HANA cannot apply DISTINCT to NCLOB JSON operands");
      let operand: string;
      if (node.filter)
        operand = `CASE WHEN ${renderNode(node.filter, scope, state, "predicate")} THEN ${node.operand ? renderNode(node.operand, scope, state) : "1"} ELSE NULL END`;
      else operand = node.operand ? renderNode(node.operand, scope, state) : "*";
      return `${node.operator}(${node.distinct ? "DISTINCT " : ""}${operand})`;
    }
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
      if (node.definition.phase !== "row")
        for (const argument of node.arguments)
          requireRowExpression(argument, `${node.definition.phase} function operand`);
      const renderer = node.definition.render[dialect.name];
      if (!renderer)
        throw new TypeError(`Function ${node.definition.name} does not support ${dialect.name}`);
      // 扩展函数是一个完整操作数；复合 OR/算术 renderer 不能泄漏优先级给外层 NOT 或乘法。
      return `(${renderRawNode(renderer(node.arguments), scope, state)})`;
    }
  }
}
