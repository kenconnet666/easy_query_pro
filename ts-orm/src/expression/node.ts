import type { AnyDataType } from "../schema/data-type.js";
import { validateName } from "../schema/table.js";
import type { WindowDefinition, WindowFunctionNode } from "./window.js";
import type { QueryNode } from "../query/model.js";

export type DialectName = "postgres" | "mysql" | "hana";
export type Phase = "row" | "aggregate" | "window";
/** declared 可提升累加输入；accumulator 保留数据库宽中间值；computed 需要显式选择原生转换。 */
export type NumericOrigin = "declared" | "accumulator" | "computed";
export type ApproximateAggregateOperator =
  "AVG" | "VAR_POP" | "VAR_SAMP" | "STDDEV_POP" | "STDDEV_SAMP";
export interface AggregateNode {
  readonly kind: "aggregate";
  readonly operator: "COUNT" | "SUM" | "MIN" | "MAX" | ApproximateAggregateOperator;
  readonly operand?: Node;
  readonly operandType?: AnyDataType;
  readonly distinct: boolean;
  readonly filter?: Node;
}

/** HANA/MySQL 的统计语法不接 DISTINCT；不能因 PG 接受就声明为共同能力。 */
export function aggregateAllowsDistinct(operator: AggregateNode["operator"]): boolean {
  return ["COUNT", "SUM", "MIN", "MAX", "AVG"].includes(operator);
}
export interface RelationIdentity {
  readonly id: symbol;
  readonly owner: symbol;
  readonly alias: string;
}
/** 扩展 renderer 组合结构化节点，最终输出顺序决定绑定顺序，重复操作数也会正确重复参数。 */
export interface FunctionDefinition {
  readonly name: string;
  readonly phase: Phase;
  /** 条件语法与 BOOLEAN 标量不是同一种 SQL 语法，HANA 在 SELECT/ORDER BY 中需显式转换。 */
  readonly syntax?: "value" | "predicate";
  readonly render: Readonly<Partial<Record<DialectName, (arguments_: readonly Node[]) => Node>>>;
}
export type BinaryOperator =
  "=" | "<>" | ">" | ">=" | "<" | "<=" | "+" | "-" | "*" | "/" | "AND" | "OR" | "LIKE";
export type Node = NodeShape & Readonly<{ outputType?: AnyDataType }>;
type NodeShape =
  | Readonly<{
      kind: "column";
      relation: RelationIdentity;
      name: string;
      type: AnyDataType;
      numericOrigin?: NumericOrigin;
    }>
  | Readonly<{ kind: "parameter"; type: AnyDataType; value: unknown }>
  | Readonly<{ kind: "binary"; operator: BinaryOperator; left: Node; right: Node }>
  | Readonly<{ kind: "unary"; operator: "NOT" | "IS NULL" | "IS NOT NULL"; operand: Node }>
  | Readonly<{
      kind: "call";
      definition: FunctionDefinition;
      arguments: readonly Node[];
      type: AnyDataType;
    }>
  | Readonly<{ kind: "in"; operand: Node; values: readonly Node[]; negated: boolean }>
  | Readonly<{ kind: "constant"; value: boolean }>
  | Readonly<{ kind: "grouping"; operand: Node }>
  | Readonly<{
      kind: "subquery";
      mode: "exists" | "scalar";
      query: QueryNode;
      type: AnyDataType;
      numericOrigin: NumericOrigin;
    }>
  | Readonly<{ kind: "cast"; operand: Node; type: AnyDataType }>
  | Readonly<{ kind: "case"; condition: Node; whenTrue: Node; whenFalse: Node }>
  | Readonly<{ kind: "coalesce" | "nullif"; left: Node; right: Node }>
  | AggregateNode
  | Readonly<{ kind: "window"; input: AggregateNode | WindowFunctionNode; spec: WindowDefinition }>
  | Readonly<{ kind: "fragment"; parts: readonly (string | Node)[] }>
  | Readonly<{ kind: "identifier"; names: readonly string[] }>;

/** 标识符是结构，不是参数值；各路径段分别引用，不能把 schema.table 当成一段已引用 SQL。 */
export function identifier(...names: readonly string[]): Node {
  if (!names.length) throw new TypeError("An identifier needs at least one segment");
  names.forEach(validateName);
  return Object.freeze({ kind: "identifier", names: Object.freeze([...names]) });
}

/** 仅用于扩展作者的 SQL 语法组合；插值必须是节点，普通业务值应先经 valueOf 编码。 */
export function sqlFragment(text: TemplateStringsArray, ...nodes: readonly Node[]): Node {
  const parts: (string | Node)[] = [];
  for (let index = 0; index < text.length; index++) {
    parts.push(text[index]!);
    if (index < nodes.length) parts.push(nodes[index]!);
  }
  return Object.freeze({ kind: "fragment", parts: Object.freeze(parts) });
}

export function freezeNode<T extends Node>(node: T): T {
  return Object.freeze(node);
}

/** 固有结果类型与附加 reader 分开：类型断言不能洗掉已有 SQL 操作数的物理存储。 */
export function inferredOutputType(node: Node): AnyDataType | undefined {
  switch (node.kind) {
    case "column":
    case "parameter":
    case "call":
    case "cast":
    case "subquery":
      return node.type;
    case "case":
      return nodeOutputType(node.whenTrue);
    case "coalesce":
    case "nullif":
      return nodeOutputType(node.left);
    case "aggregate":
      return node.operator === "MIN" || node.operator === "MAX" ? node.operandType : undefined;
    case "window":
      return node.input.kind === "aggregate"
        ? nodeOutputType(node.input)
        : node.input.operand
          ? nodeOutputType(node.input.operand)
          : undefined;
    default:
      return undefined;
  }
}
export function nodeOutputType(node: Node): AnyDataType | undefined {
  return node.outputType ?? inferredOutputType(node);
}

/** 遍历保留所有内层语义，CAST/算术等包装不能掩盖聚合或窗口。子查询会有独立编译边界。 */
export function visitNode(node: Node, visit: (node: Node) => void | boolean): void {
  if (visit(node) === false) return;
  switch (node.kind) {
    case "binary":
    case "coalesce":
    case "nullif":
      visitNode(node.left, visit);
      visitNode(node.right, visit);
      break;
    case "unary":
    case "cast":
    case "grouping":
      visitNode(node.operand, visit);
      break;
    case "call":
      for (const argument of node.arguments) visitNode(argument, visit);
      break;
    case "case":
      visitNode(node.condition, visit);
      visitNode(node.whenTrue, visit);
      visitNode(node.whenFalse, visit);
      break;
    case "aggregate":
      if (node.operand) visitNode(node.operand, visit);
      if (node.filter) visitNode(node.filter, visit);
      break;
    case "window":
      if (node.input.kind === "aggregate") visitNode(node.input, visit);
      else {
        if (node.input.operand) visitNode(node.input.operand, visit);
        if (node.input.fallback) visitNode(node.input.fallback, visit);
      }
      for (const partition of node.spec.partitions) visitNode(partition, visit);
      for (const order of node.spec.ordering) visitNode(order.expression, visit);
      break;
    case "in":
      visitNode(node.operand, visit);
      for (const value of node.values) visitNode(value, visit);
      break;
    case "fragment":
      for (const part of node.parts) if (typeof part !== "string") visitNode(part, visit);
      break;
  }
}

export function combineNumericOrigin(...origins: readonly NumericOrigin[]): NumericOrigin {
  return origins.includes("computed")
    ? "computed"
    : origins.includes("accumulator")
      ? "accumulator"
      : "declared";
}
export function numericOrigin(node: Node): NumericOrigin {
  switch (node.kind) {
    case "window": {
      const input = node.input;
      if (input.kind === "aggregate") return numericOrigin(input);
      if (!input.operand) return "declared";
      return combineNumericOrigin(
        numericOrigin(input.operand),
        ...(input.fallback ? [numericOrigin(input.fallback)] : []),
      );
    }
    case "column":
    case "subquery":
      return node.numericOrigin ?? "computed";
    case "parameter":
    case "cast":
    case "grouping":
      return "declared";
    case "case":
      return combineNumericOrigin(numericOrigin(node.whenTrue), numericOrigin(node.whenFalse));
    case "coalesce":
      return combineNumericOrigin(numericOrigin(node.left), numericOrigin(node.right));
    case "nullif":
      return numericOrigin(node.left);
    case "aggregate":
      if (node.operator === "SUM") return "accumulator";
      if (node.operator === "COUNT") return "declared";
      if (node.operator === "MIN" || node.operator === "MAX") return numericOrigin(node.operand!);
      return "computed";
    default:
      return "computed";
  }
}
