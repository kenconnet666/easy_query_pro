import { nodeOutputType, visitNode, type Node } from "../expression/node.js";
import type { SelectNode } from "../query/model.js";
import { requireRowExpression } from "./expression.js";
import { freeQueryColumns, sameGroupingKey } from "../query/analysis.js";

function isAggregate(node: Node): boolean {
  return (
    node.kind === "aggregate" || (node.kind === "call" && node.definition.phase === "aggregate")
  );
}
function isWindow(node: Node): boolean {
  return node.kind === "window" || (node.kind === "call" && node.definition.phase === "window");
}

export function validateGrouping(query: SelectNode): void {
  if (query.having)
    visitNode(query.having, (node) => {
      if (isWindow(node))
        throw new TypeError("HAVING cannot contain a same-level window; use a derived query");
    });
  for (const key of query.grouping) {
    requireRowExpression(key, "GROUP BY");
    visitNode(key, (node) => {
      if (node.kind === "parameter" || node.kind === "subquery")
        throw new TypeError("Parameterized GROUP BY keys require a derived source");
    });
  }
  const expressions = [
    ...query.projection.map((slot) => slot.node),
    ...query.ordering.map((order) => order.expression),
    ...(query.having ? [query.having] : []),
  ];
  let grouped = query.grouping.length > 0 || query.having !== undefined;
  for (const expression of expressions)
    visitNode(expression, (node) => {
      if (isWindow(node)) return false;
      if (isAggregate(node)) {
        grouped = true;
        return false;
      }
    });
  const matchesKey = (node: Node) => query.grouping.some((key) => sameGroupingKey(key, node));
  for (const expression of expressions)
    visitNode(expression, (node) => {
      if (node.kind === "grouping") {
        if (query.groupingMode !== "rollup" || !matchesKey(node.operand))
          throw new TypeError("GROUPING requires a key from this ROLLUP query");
        return false;
      }
      if (grouped && isWindow(node))
        throw new TypeError("Apply windows to grouped results through a derived source or CTE");
      if (
        grouped &&
        node.kind === "subquery" &&
        freeQueryColumns(node.query).some((column) =>
          query.relations.some((relation) => relation.id === column.relation.id),
        )
      )
        throw new TypeError(
          "Apply correlated subqueries to grouped results through a derived source or CTE",
        );
      if (matchesKey(node)) return false;
      if (isAggregate(node)) return false;
      if (grouped && node.kind === "column")
        throw new TypeError(
          "An ungrouped column appears outside an aggregate; reuse the named group key or add a derived query",
        );
    });
  if (query.groupingMode === "rollup") {
    for (const slot of query.projection) {
      if (!slot.type.acceptsNull && rollupNullable(slot.node, matchesKey))
        throw new TypeError(
          "A ROLLUP projection must preserve subtotal NULL; use its nullable scope/group keys or explicit coalesce",
        );
    }
  }
}

/** 旧 scope 可被闭包重新捕获；不能只修 decoder 后仍把小计键声明为非空值。 */
function rollupNullable(node: Node, matchesKey: (node: Node) => boolean): boolean {
  if (matchesKey(node)) return true;
  if (nodeOutputType(node)?.acceptsNull) return true;
  switch (node.kind) {
    case "grouping":
    case "constant":
    case "identifier":
      return false;
    case "column":
      return node.type.acceptsNull;
    case "parameter":
      return node.value === null;
    case "aggregate":
      return node.operator !== "COUNT";
    case "subquery":
      return node.mode === "scalar";
    case "unary":
      return node.operator === "NOT" && rollupNullable(node.operand, matchesKey);
    case "cast":
      return rollupNullable(node.operand, matchesKey);
    case "nullif":
      return true;
    case "coalesce":
      return rollupNullable(node.left, matchesKey) && rollupNullable(node.right, matchesKey);
    case "case":
      return (
        rollupNullable(node.whenTrue, matchesKey) || rollupNullable(node.whenFalse, matchesKey)
      );
    case "binary":
      if (node.operator === "IS NOT DISTINCT FROM" || node.operator === "IS DISTINCT FROM")
        return false;
      return rollupNullable(node.left, matchesKey) || rollupNullable(node.right, matchesKey);
    case "in":
      return (
        rollupNullable(node.operand, matchesKey) ||
        node.values.some((value) => rollupNullable(value, matchesKey))
      );
    case "call":
      return (
        node.definition.phase === "row" &&
        node.arguments.some((value) => rollupNullable(value, matchesKey))
      );
    case "fragment":
      return node.parts.some(
        (part) => typeof part !== "string" && rollupNullable(part, matchesKey),
      );
    case "window":
      return true;
  }
}
