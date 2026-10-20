import { Expression, and, type AnyExpression, type Predicate } from "./expression.js";
import { freezeNode, numericOrigin, type AggregateNode } from "./node.js";
import { types, type DataType, type SqlKind } from "../schema/data-type.js";
import { windowExpression, type WindowSpec } from "./window.js";

export class AggregateExpression<T, K extends SqlKind> extends Expression<T, K> {
  over(spec: WindowSpec): Expression<T, K> {
    return windowExpression(this.aggregateNode, this.type, spec);
  }
  constructor(
    private readonly aggregateNode: AggregateNode,
    type: DataType<T, K>,
  ) {
    super({ node: aggregateNode, type });
  }
  distinct(): AggregateExpression<T, K> {
    if (!this.aggregateNode.operand)
      throw new TypeError("COUNT DISTINCT requires an explicit operand");
    return new AggregateExpression(
      freezeNode({ ...this.aggregateNode, distinct: true }),
      this.type,
    );
  }
  /** 条件不满足时贡献 NULL；SUM 的空输入语义不会被错误改成 0。 */
  filter(predicate: Predicate): AggregateExpression<T, K> {
    if (!(predicate instanceof Expression) || predicate.type.storage.kind !== "boolean")
      throw new TypeError("Aggregate filters require a boolean expression");
    const previous = this.aggregateNode.filter;
    const filter = previous
      ? and(new Expression({ node: previous, type: types.boolean().nullable() }), predicate).node
      : predicate.node;
    return new AggregateExpression(freezeNode({ ...this.aggregateNode, filter }), this.type);
  }
}

type Numeric = "integer" | "bigint" | "decimal";
type Ordered = Numeric | "text";
function requireOperand(value: AnyExpression, allowed: readonly SqlKind[]): void {
  if (!(value instanceof Expression) || !allowed.includes(value.type.storage.kind))
    throw new TypeError("Aggregate operand has an unsupported SQL type");
}

export const aggregate = {
  count(value?: AnyExpression): AggregateExpression<bigint, "bigint"> {
    if (value !== undefined && !(value instanceof Expression))
      throw new TypeError("COUNT expects a typed expression; use valueOf for SQL NULL");
    return new AggregateExpression(
      freezeNode({
        kind: "aggregate",
        operator: "COUNT",
        distinct: false,
        ...(value ? { operand: value.node, operandType: value.type } : {}),
      }),
      types.bigint(),
    );
  },
  sum<T, K extends Numeric>(
    value: Expression<T, K>,
  ): AggregateExpression<string | null, "decimal"> {
    requireOperand(value, ["integer", "bigint", "decimal"]);
    const origin = numericOrigin(value.node);
    if (origin === "computed")
      throw new TypeError(
        "SUM of computed numeric values requires an explicit nativeCast boundary",
      );
    const type = types.decimal(
      38,
      value.type.storage.kind === "decimal" ? value.type.storage.scale! : 0,
    );
    // HANA 的 INTEGER/BIGINT 累加会保留原类型，因此先提升输入。
    // 已有累计值不能再次窄 CAST，避免 MySQL 将宽中间值夹回 38 位而不报错。
    const operand =
      origin === "accumulator"
        ? value.node
        : freezeNode({ kind: "cast" as const, operand: value.node, type });
    return new AggregateExpression(
      freezeNode({
        kind: "aggregate",
        operator: "SUM",
        operand,
        operandType: type,
        distinct: false,
      }),
      type.nullable(),
    );
  },
  min<T, K extends Ordered>(value: Expression<T, K>): AggregateExpression<T | null, K> {
    requireOperand(value, ["integer", "bigint", "decimal", "text"]);
    return new AggregateExpression(
      freezeNode({
        kind: "aggregate",
        operator: "MIN",
        operand: value.node,
        operandType: value.type,
        distinct: false,
      }),
      value.type.nullable(),
    );
  },
  max<T, K extends Ordered>(value: Expression<T, K>): AggregateExpression<T | null, K> {
    requireOperand(value, ["integer", "bigint", "decimal", "text"]);
    return new AggregateExpression(
      freezeNode({
        kind: "aggregate",
        operator: "MAX",
        operand: value.node,
        operandType: value.type,
        distinct: false,
      }),
      value.type.nullable(),
    );
  },
};
