import { parameterBudget, type Dialect } from "../dialects/dialect.js";
import {
  aggregateAllowsDistinct,
  visitNode,
  nodeOutputType,
  type Node,
} from "../expression/node.js";
import { validateWindow, type RowBoundary } from "../expression/window.js";
import type { Order, QueryNode } from "../query/model.js";
import {
  requireTypeOperation,
  requireNativeSupport,
  type TypeOperation,
} from "../schema/native-type.js";
import { sameGroupingKey } from "../query/analysis.js";

export function requireNodeOperation(node: Node, dialect: Dialect, operation: TypeOperation): void {
  const storage = nodeOutputType(node)?.storage;
  if (storage) requireTypeOperation(storage, dialect.name, operation);
}

export type Scope = ReadonlyMap<symbol, string>;
export interface State {
  dialect: Dialect;
  owner: symbol;
  parameters: unknown[];
  parameterLimit: number;
  groupingScopes: WeakMap<Scope, readonly Node[]>;
  /** 仅查询编译器安装；普通 command/写入尚未声明子查询与 CTE 的作用域合同。 */
  renderSubquery?: (query: QueryNode, scope: Scope) => string;
}

/** 不附参数值，避免错误日志暴露业务数据；attempted 是触发失败的位置，不伪装完整计划总数。 */
export class ParameterLimitError extends RangeError {
  constructor(
    readonly dialect: Dialect["name"],
    readonly limit: number,
    readonly attempted: number,
  ) {
    super(`${dialect} statement exceeded its parameter budget of ${limit} at binding ${attempted}`);
    this.name = "ParameterLimitError";
  }
}

/** 手写 Dialect 也在编译入口校验，不能只保护标准工厂；每条语句固定预算快照。 */
export function createState(dialect: Dialect, owner: symbol): State {
  return {
    dialect,
    owner,
    parameters: [],
    parameterLimit: parameterBudget(dialect.maxParameters),
    groupingScopes: new WeakMap(),
  };
}

export function requireRowExpression(node: Node, clause: string): void {
  visitNode(node, (current) => {
    if (current.kind === "window")
      throw new TypeError(
        `${clause} cannot contain a same-level window; project it into a derived query first`,
      );
    if (current.kind === "aggregate" || current.kind === "grouping")
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
  if (node.kind === "subquery") return node.mode === "exists";
  if (node.kind === "in" || node.kind === "unary" || node.kind === "constant") return true;
  if (node.kind === "binary") return !["+", "-", "*", "/"].includes(node.operator);
  return node.kind === "call" && node.definition.syntax === "predicate";
}

/** 只优化 SQL 本身保证二值的结构，不凭扩展作者声明的 reader 类型吞掉可能的 UNKNOWN。 */
function isTwoValuedPredicate(node: Node): boolean {
  if (node.kind === "constant" || (node.kind === "subquery" && node.mode === "exists")) return true;
  if (node.kind === "unary") return node.operator !== "NOT" || isTwoValuedPredicate(node.operand);
  return (
    node.kind === "binary" &&
    (node.operator === "IS DISTINCT FROM" || node.operator === "IS NOT DISTINCT FROM")
  );
}

export function renderNode(
  node: Node,
  scope: Scope,
  state: State,
  context: "value" | "predicate" = "value",
): string {
  if (state.dialect.name === "hana") {
    const predicate = isPredicate(node);
    // 二值谓词只需一个 WHEN，复杂操作数/子查询也只按实际 SQL 出现位置绑定一次。
    if (context === "value" && predicate && isTwoValuedPredicate(node))
      return `(CASE WHEN ${renderRawNode(node, scope, state)} THEN TRUE ELSE FALSE END)`;
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
  // renderer 展开后也经过这里，不能让新产生的 typed fragment 绕过当前方言检查。
  if (node.outputType) requireNativeSupport(node.outputType.storage, dialect.name);
  switch (node.kind) {
    case "grouping": {
      const keys = state.groupingScopes.get(scope);
      if (!keys?.some((key) => sameGroupingKey(key, node.operand)))
        throw new TypeError("GROUPING requires a key from this ROLLUP query");
      return `GROUPING(${renderNode(node.operand, scope, state)})`;
    }
    case "subquery": {
      if (!state.renderSubquery)
        throw new TypeError("Expression subqueries currently require a SELECT query");
      const query = state.renderSubquery(node.query, scope);
      // MySQL 的直接 EXISTS 会忽略 DISTINCT 投影，OFFSET 因而可能按原始行数计数。
      // 独立关系边界先完成去重/分页，再判断是否有行；保留原绑定与相关作用域。
      if (
        node.mode === "exists" &&
        dialect.name === "mysql" &&
        node.query.kind === "select" &&
        node.query.distinct &&
        node.query.offset > 0
      ) {
        const used = new Set([...scope.values()].map((alias) => alias.toLowerCase()));
        let alias = "_eq_exists";
        for (let index = 1; used.has(alias.toLowerCase()); index++) alias = "_eq_exists_" + index;
        return `EXISTS (SELECT 1 FROM (${query}) ${dialect.quote(alias)})`;
      }
      return node.mode === "exists" ? `EXISTS (${query})` : `(${query})`;
    }
    case "column": {
      requireNativeSupport(node.type.storage, dialect.name);
      const alias = scope.get(node.relation.id);
      if (node.relation.owner !== state.owner || alias === undefined)
        throw new TypeError("Column is outside this query scope");
      return (alias === "" ? "" : dialect.quote(alias) + ".") + dialect.quote(node.name);
    }
    case "parameter":
      if (state.parameters.length >= state.parameterLimit)
        throw new ParameterLimitError(
          dialect.name,
          state.parameterLimit,
          state.parameters.length + 1,
        );
      state.parameters.push(node.value);
      return dialect.parameter(state.parameters.length, node.type.storage);
    case "cast":
      return dialect.cast(renderNode(node.operand, scope, state), node.type.storage);
    case "window": {
      validateWindow(node.input, node.spec);
      const input = node.input;
      let fn: string;
      if (input.kind === "aggregate") fn = renderRawNode(input, scope, state);
      else {
        const operands: string[] = [];
        if (input.operand) {
          requireRowExpression(input.operand, "Window operand");
          operands.push(renderNode(input.operand, scope, state));
        }
        // LAG/LEAD 等位置使用已校验的整数，不走 CAST(?) 文法不兼容的参数路径。
        if (input.offset !== undefined) operands.push(String(input.offset));
        if (input.fallback) {
          requireRowExpression(input.fallback, "Window default");
          operands.push(renderNode(input.fallback, scope, state));
        }
        fn = `${input.name}(${operands.join(", ")})`;
      }
      const parts: string[] = [];
      if (node.spec.partitions.length)
        parts.push(
          "PARTITION BY " +
            node.spec.partitions
              .map((partition) => {
                requireRowExpression(partition, "Window partition");
                requireNodeOperation(partition, dialect, "grouping");
                return renderNode(partition, scope, state);
              })
              .join(", "),
        );
      if (node.spec.ordering.length) {
        for (const order of node.spec.ordering)
          requireRowExpression(order.expression, "Window ORDER BY");
        parts.push("ORDER BY " + renderOrders(node.spec.ordering, scope, state));
      }
      if (node.spec.frame)
        parts.push(
          `ROWS BETWEEN ${renderBoundary(node.spec.frame.start)} AND ${renderBoundary(node.spec.frame.end)}`,
        );
      return `${fn} OVER (${parts.join(" ")})`;
    }
    case "case":
      return `(CASE WHEN ${renderNode(node.condition, scope, state, "predicate")} THEN ${renderNode(node.whenTrue, scope, state)} ELSE ${renderNode(node.whenFalse, scope, state)} END)`;
    case "nullif":
      requireNodeOperation(node.left, dialect, "equality");
      requireNodeOperation(node.right, dialect, "equality");
      return `NULLIF(${renderNode(node.left, scope, state)}, ${renderNode(node.right, scope, state)})`;
    case "coalesce":
      return `${node.kind.toUpperCase()}(${renderNode(node.left, scope, state)}, ${renderNode(node.right, scope, state)})`;
    case "aggregate": {
      if (node.distinct && !aggregateAllowsDistinct(node.operator))
        throw new TypeError(
          "Statistical aggregates do not support DISTINCT; deduplicate a derived source first",
        );
      if (node.operand) requireRowExpression(node.operand, "Aggregate operand");
      if (node.filter) requireRowExpression(node.filter, "Aggregate filter");
      if (node.operandType) {
        if (node.distinct) requireTypeOperation(node.operandType.storage, dialect.name, "grouping");
        if (node.operator === "MIN" || node.operator === "MAX")
          requireTypeOperation(node.operandType.storage, dialect.name, "ordering");
      }
      let operand: string;
      const renderOperand = () => {
        if (!node.operand) return node.filter ? "1" : "*";
        const rendered = renderNode(node.operand, scope, state);
        // HANA 窗口聚合连 CASE WHEN lob IS NOT NULL 都拒绝。LENGTH 先变为标量，
        // 恰好只在 SQL NULL 时返回 NULL；空文档计为 0 仍会被 COUNT 计入，不截断文本。
        return node.operator === "COUNT" &&
          node.operandType?.storage.kind === "json" &&
          dialect.name === "hana"
          ? `LENGTH(${rendered})`
          : rendered;
      };
      if (node.filter)
        operand = `CASE WHEN ${renderNode(node.filter, scope, state, "predicate")} THEN ${renderOperand()} ELSE NULL END`;
      else operand = renderOperand();
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
      if (node.operator === "IS NOT DISTINCT FROM" || node.operator === "IS DISTINCT FROM") {
        requireNodeOperation(node.left, dialect, "equality");
        requireNodeOperation(node.right, dialect, "equality");
        const left = renderNode(node.left, scope, state);
        const right = renderNode(node.right, scope, state);
        const different = node.operator === "IS DISTINCT FROM";
        if (dialect.name === "postgres") return `(${left} ${node.operator} ${right})`;
        if (dialect.name === "mysql")
          return different ? `(NOT (${left} <=> ${right}))` : `(${left} <=> ${right})`;
        return `(MAP(${left}, ${right}, 1, 0) = ${different ? 0 : 1})`;
      }
      if (["=", "<>", ">", ">=", "<", "<="].includes(node.operator)) {
        const operation = node.operator === "=" || node.operator === "<>" ? "equality" : "ordering";
        requireNodeOperation(node.left, dialect, operation);
        requireNodeOperation(node.right, dialect, operation);
      }
      const context = node.operator === "AND" || node.operator === "OR" ? "predicate" : "value";
      return `(${renderNode(node.left, scope, state, context)} ${node.operator} ${renderNode(node.right, scope, state, context)})`;
    }
    case "unary":
      return node.operator === "NOT"
        ? `(NOT ${renderNode(node.operand, scope, state, "predicate")})`
        : `(${renderNode(node.operand, scope, state)} ${node.operator})`;
    case "in":
      requireNodeOperation(node.operand, dialect, "membership");
      return `(${renderNode(node.operand, scope, state)} ${node.negated ? "NOT IN" : "IN"} (${node.values.map((value) => renderNode(value, scope, state)).join(", ")}))`;
    case "call": {
      requireNativeSupport(node.type.storage, dialect.name);
      if (node.definition.phase !== "row")
        for (const argument of node.arguments)
          requireRowExpression(argument, `${node.definition.phase} function operand`);
      const renderer = node.definition.render[dialect.name];
      if (!renderer)
        throw new TypeError(`Function ${node.definition.name} does not support ${dialect.name}`);
      const rendered = renderer(node.arguments);
      // renderer 可重排/重复已声明操作数，不能通过闭包新增查询依赖边，否则 WITH 排序和分组分析会漏读。
      const declaredQueries = new Set<QueryNode>();
      const declaredColumns: Extract<Node, { kind: "column" }>[] = [];
      const declaredGroupingKeys: Node[] = [];
      for (const argument of node.arguments)
        visitNode(argument, (child) => {
          if (child.kind === "subquery") declaredQueries.add(child.query);
          if (child.kind === "column") declaredColumns.push(child);
          if (child.kind === "grouping") declaredGroupingKeys.push(child.operand);
        });
      visitNode(rendered, (child) => {
        if (child.kind === "subquery" && !declaredQueries.has(child.query))
          throw new TypeError(
            "A renderer must receive subqueries through its declared expression arguments",
          );
        if (
          child.kind === "column" &&
          !declaredColumns.some((column) => sameGroupingKey(column, child))
        )
          throw new TypeError(
            "A renderer must receive column references through its declared expression arguments",
          );
        if (
          child.kind === "grouping" &&
          !declaredGroupingKeys.some((key) => sameGroupingKey(key, child.operand))
        )
          throw new TypeError(
            "A renderer must receive GROUPING nodes through its declared expression arguments",
          );
      });
      const renderedType = nodeOutputType(rendered);
      if (renderedType) {
        requireNativeSupport(renderedType.storage, dialect.name);
        if (!node.type.hasSameStorage(renderedType))
          throw new TypeError(
            `Function ${node.definition.name} renderer cannot change SQL storage without an explicit conversion`,
          );
      }
      // 扩展函数是一个完整操作数；复合 OR/算术 renderer 不能泄漏优先级给外层 NOT 或乘法。
      return `(${renderRawNode(rendered, scope, state)})`;
    }
  }
}

function renderBoundary(boundary: RowBoundary): string {
  if (boundary.kind === "current") return "CURRENT ROW";
  const count = boundary.kind === "unbounded" ? "UNBOUNDED" : String(boundary.count);
  return `${count} ${boundary.direction.toUpperCase()}`;
}

/** 普通和窗口排序共用一处 NULLS 语义；MySQL 多出的排序键各自按输出位置绑定。 */
export function renderOrders(orders: readonly Order[], scope: Scope, state: State): string {
  return orders
    .map((order) => {
      requireNodeOperation(order.expression, state.dialect, "sorting");
      if (order.nulls && state.dialect.name === "mysql") {
        const first = order.nulls === "FIRST";
        return `CASE WHEN ${renderNode(order.expression, scope, state)} IS NULL THEN ${first ? 0 : 1} ELSE ${first ? 1 : 0} END ASC, ${renderNode(order.expression, scope, state)} ${order.direction}`;
      }
      return `${renderNode(order.expression, scope, state)} ${order.direction}${order.nulls ? " NULLS " + order.nulls : ""}`;
    })
    .join(", ");
}
