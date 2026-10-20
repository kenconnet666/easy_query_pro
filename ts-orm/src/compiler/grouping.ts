import { visitNode, type Node } from "../expression/node.js";
import type { SelectNode } from "../query/model.js";
import { requireRowExpression } from "./expression.js";
import { freeQueryColumns } from "../query/analysis.js";

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
  const matchesKey = (node: Node) =>
    query.grouping.some(
      (key) =>
        key === node ||
        (key.kind === "column" &&
          node.kind === "column" &&
          key.relation.id === node.relation.id &&
          key.name === node.name),
    );
  for (const expression of expressions)
    visitNode(expression, (node) => {
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
}
