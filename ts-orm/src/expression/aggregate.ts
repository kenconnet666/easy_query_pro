import { Expression, and, type AnyExpression, type Predicate } from "./expression.js";
import {
  aggregateAllowsDistinct,
  freezeNode,
  numericOrigin,
  type AggregateNode,
  type ApproximateAggregateOperator,
} from "./node.js";
import { types, type DataType, type SqlKind } from "../schema/data-type.js";
import { windowExpression, type WindowSpec } from "./window.js";

export class AggregateExpression<
  T,
  K extends SqlKind,
  Distinct extends boolean = true,
> extends Expression<T, K> {
  private readonly distinctAllowed: Distinct;
  over(spec: WindowSpec): Expression<T, K> {
    return windowExpression(this.aggregateNode, this.type, spec);
  }
  constructor(
    private readonly aggregateNode: AggregateNode,
    type: DataType<T, K>,
  ) {
    super({ node: aggregateNode, type });
    // 公共工厂的第三泛型与操作种类同步；底层构造即使绕过类型，也由运行时与编译器再次保护。
    this.distinctAllowed = aggregateAllowsDistinct(aggregateNode.operator) as Distinct;
  }
  distinct(
    this: Distinct extends true ? AggregateExpression<T, K, Distinct> : never,
  ): AggregateExpression<T, K> {
    if (!this.distinctAllowed)
      throw new TypeError(
        "Statistical aggregates do not support DISTINCT; deduplicate a derived source first",
      );
    if (!this.aggregateNode.operand)
      throw new TypeError("COUNT DISTINCT requires an explicit operand");
    return new AggregateExpression(
      freezeNode({ ...this.aggregateNode, distinct: true }),
      this.type,
    );
  }
  /** 条件不满足时贡献 NULL；SUM 的空输入语义不会被错误改成 0。 */
  filter(predicate: Predicate): AggregateExpression<T, K, Distinct> {
    if (!(predicate instanceof Expression) || predicate.type.storage.kind !== "boolean")
      throw new TypeError("Aggregate filters require a boolean expression");
    const previous = this.aggregateNode.filter;
    const filter = previous
      ? and(new Expression({ node: previous, type: types.boolean().nullable() }), predicate).node
      : predicate.node;
    return new AggregateExpression<T, K, Distinct>(
      freezeNode({ ...this.aggregateNode, filter }),
      this.type,
    );
  }
}

type Numeric = "integer" | "bigint" | "decimal";
type Ordered = Numeric | "float64" | "text" | "date" | "localDateTime";
function requireOperand(value: AnyExpression, allowed: readonly SqlKind[]): void {
  if (!(value instanceof Expression) || !allowed.includes(value.type.storage.kind))
    throw new TypeError("Aggregate operand has an unsupported SQL type");
}

/** 近似统计共同选择 DOUBLE 输入，避免结果后置 CAST 掩盖已发生的整数/DECIMAL 运算损失。 */
function approximateAggregate<
  T,
  K extends Numeric | "float64",
  O extends ApproximateAggregateOperator,
>(
  operator: O,
  value: Expression<T, K>,
): AggregateExpression<number | null, "float64", O extends "AVG" ? true : false> {
  requireOperand(value, ["integer", "bigint", "decimal", "float64"]);
  const type = types.float64();
  const operand =
    value.type.storage.kind === "float64"
      ? value.node
      : freezeNode({ kind: "cast" as const, operand: value.node, type });
  return new AggregateExpression(
    freezeNode({ kind: "aggregate", operator, operand, operandType: type, distinct: false }),
    type.nullable(),
  );
}

export const aggregate = {
  /**
   * 在 AVG 之前显式选择 DOUBLE 运算域，而非结果返回后才 CAST。
   * 名称说明近似计算：DECIMAL/BIGINT 输入转换会损失精度，不伪装成三库统一的精确平均值。
   */
  avgApprox<T, K extends Numeric | "float64">(
    value: Expression<T, K>,
  ): AggregateExpression<number | null, "float64"> {
    return approximateAggregate("AVG", value);
  },
  /** 总体统计以有效非 NULL 样本数为分母，单个有效样本的方差为 0。 */
  variancePopulationApprox<T, K extends Numeric | "float64">(value: Expression<T, K>) {
    return approximateAggregate("VAR_POP", value);
  },
  /** 样本统计使用 n-1；有效样本少于两个时保持 SQL NULL。 */
  varianceSampleApprox<T, K extends Numeric | "float64">(value: Expression<T, K>) {
    return approximateAggregate("VAR_SAMP", value);
  },
  standardDeviationPopulationApprox<T, K extends Numeric | "float64">(value: Expression<T, K>) {
    return approximateAggregate("STDDEV_POP", value);
  },
  standardDeviationSampleApprox<T, K extends Numeric | "float64">(value: Expression<T, K>) {
    return approximateAggregate("STDDEV_SAMP", value);
  },
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
    requireOperand(value, [
      "integer",
      "bigint",
      "decimal",
      "float64",
      "text",
      "date",
      "localDateTime",
    ]);
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
    requireOperand(value, [
      "integer",
      "bigint",
      "decimal",
      "float64",
      "text",
      "date",
      "localDateTime",
    ]);
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
