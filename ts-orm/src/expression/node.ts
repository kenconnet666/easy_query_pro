import type { AnyDataType } from "../schema/data-type.js";
import { validateName } from "../schema/table.js";

export type DialectName = "postgres" | "mysql" | "hana";
export type Phase = "row" | "aggregate" | "window";
export interface RelationIdentity {
  readonly id: symbol;
  readonly owner: symbol;
  readonly alias: string;
}
/** 扩展 renderer 组合结构化节点，最终输出顺序决定绑定顺序，重复操作数也会正确重复参数。 */
export interface FunctionDefinition {
  readonly name: string;
  readonly phase: Phase;
  readonly render: Readonly<Partial<Record<DialectName, (arguments_: readonly Node[]) => Node>>>;
}
export type BinaryOperator =
  "=" | "<>" | ">" | ">=" | "<" | "<=" | "+" | "-" | "*" | "/" | "AND" | "OR" | "LIKE";
export type Node =
  | Readonly<{ kind: "column"; relation: RelationIdentity; name: string }>
  | Readonly<{ kind: "parameter"; type: AnyDataType; value: unknown }>
  | Readonly<{ kind: "binary"; operator: BinaryOperator; left: Node; right: Node }>
  | Readonly<{ kind: "unary"; operator: "NOT" | "IS NULL" | "IS NOT NULL"; operand: Node }>
  | Readonly<{ kind: "call"; definition: FunctionDefinition; arguments: readonly Node[] }>
  | Readonly<{ kind: "in"; operand: Node; values: readonly Node[]; negated: boolean }>
  | Readonly<{ kind: "constant"; value: boolean }>
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

/** 遍历保留所有内层语义，CAST/算术等包装不能掩盖聚合或窗口。子查询会有独立编译边界。 */
export function visitNode(node: Node, visit: (node: Node) => void): void {
  visit(node);
  switch (node.kind) {
    case "binary":
      visitNode(node.left, visit);
      visitNode(node.right, visit);
      break;
    case "unary":
      visitNode(node.operand, visit);
      break;
    case "call":
      for (const argument of node.arguments) visitNode(argument, visit);
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
