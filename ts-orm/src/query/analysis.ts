import { visitNode, type Node } from "../expression/node.js";
import { DerivedSource, type QueryNode, type SelectNode } from "./model.js";

/** 当前 SELECT 层的表达式根；子查询、派生关系与 CTE 由调用者选择是否继续进入。 */
export function queryExpressions(query: SelectNode): readonly Node[] {
  return [
    ...query.projection.map((slot) => slot.node),
    ...query.grouping,
    ...query.ordering.map((order) => order.expression),
    ...(query.where ? [query.where] : []),
    ...(query.having ? [query.having] : []),
    ...query.relations.flatMap((relation) => (relation.on ? [relation.on] : [])),
  ];
}

type ColumnNode = Extract<Node, { kind: "column" }>;

/**
 * 自由列是本查询没有绑定、必须从祖先作用域捕获的列。内部聚合不会改变列所属关系。
 * 普通 FROM/CTE 没有 LATERAL 合同，不能把捕获引用偷偷提升或当成兄弟关系读取。
 */
export function freeQueryColumns(root: QueryNode): readonly ColumnNode[] {
  const complete = new Map<QueryNode, readonly ColumnNode[]>();
  const visiting = new Set<QueryNode>();
  function inspect(query: QueryNode): readonly ColumnNode[] {
    const known = complete.get(query);
    if (known) return known;
    if (visiting.has(query)) throw new TypeError("Recursive query references are not supported");
    visiting.add(query);
    const columns: ColumnNode[] = [];
    if (query.kind === "set") columns.push(...inspect(query.left), ...inspect(query.right));
    else {
      const local = new Set(query.relations.map((relation) => relation.id));
      const add = (column: ColumnNode) => {
        if (!local.has(column.relation.id)) columns.push(column);
      };
      for (const expression of queryExpressions(query))
        visitNode(expression, (node) => {
          if (node.kind === "column") add(node);
          if (node.kind === "subquery") inspect(node.query).forEach(add);
        });
      for (const relation of query.relations) {
        if (relation.source instanceof DerivedSource && inspect(relation.source.query).length)
          throw new TypeError(
            "A derived source or CTE cannot capture outer columns; use an expression subquery",
          );
      }
    }
    visiting.delete(query);
    const result = Object.freeze(columns);
    complete.set(query, result);
    return result;
  }
  return inspect(root);
}

/** 表达式内的子查询是聚合层边界，但其自由列仍受外围 GROUP BY 等语义约束。 */
export function expressionColumns(expression: Node): readonly ColumnNode[] {
  const columns: ColumnNode[] = [];
  visitNode(expression, (node) => {
    if (node.kind === "column") columns.push(node);
    if (node.kind === "subquery") columns.push(...freeQueryColumns(node.query));
  });
  return columns;
}
