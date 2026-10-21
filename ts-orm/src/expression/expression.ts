import { DataType, types, type SqlKind } from "../schema/data-type.js";
import {
  freezeNode,
  inferredOutputType,
  sqlFragment,
  type BinaryOperator,
  type FunctionDefinition,
  type Node,
} from "./node.js";
import type { Order } from "../query/model.js";

type Numeric = "integer" | "bigint" | "decimal" | "float64";
export type AnyExpression = Expression<unknown, SqlKind>;
export type Predicate = Expression<boolean | null, "boolean">;
export interface ExpressionBinding<T, K extends SqlKind = SqlKind> {
  readonly node: Node;
  readonly type: DataType<T, K>;
}
export type Input<T, K extends SqlKind> = T | Expression<T, K>;

/**
 * 值类型 T 必须出现在运行时类型载体中，不能仅写一个未使用的泛型参数。
 * 默认操作返回明确的表达式族；应用用 mount(factory) 保留自定义表达式的具体 API。
 */
export class Expression<T, K extends SqlKind = SqlKind> {
  readonly node: Node;
  readonly type: DataType<T, K>;

  constructor(binding: ExpressionBinding<T, K>) {
    const inferred = inferredOutputType(binding.node);
    if (
      (binding.node.outputType && !binding.type.hasSameStorage(binding.node.outputType)) ||
      (inferred && !binding.type.hasSameStorage(inferred))
    )
      throw new TypeError(
        "An expression reader cannot change SQL storage; use nativeCast for a physical conversion",
      );
    // 低层 fragment 构造也必须携带输出存储；已有节点的物理 SQL 类型不因换 reader 或 nullable 而改变。
    this.node = binding.node.outputType
      ? binding.node
      : freezeNode({ ...binding.node, outputType: binding.type });
    this.type = binding.type;
  }

  mount<E>(factory: (binding: ExpressionBinding<T, K>) => E): E {
    return factory(Object.freeze({ node: this.node, type: this.type }));
  }

  nullable(): Expression<T | null, K> {
    return new Expression({ node: this.node, type: this.type.nullable() });
  }

  asc(nulls?: "first" | "last"): Order {
    return this.order("ASC", nulls);
  }
  desc(nulls?: "first" | "last"): Order {
    return this.order("DESC", nulls);
  }
  private order(direction: Order["direction"], nulls?: "first" | "last"): Order {
    if (nulls !== undefined && nulls !== "first" && nulls !== "last")
      throw new TypeError("NULL placement must be first or last");
    return Object.freeze({
      expression: this.node,
      direction,
      ...(nulls === undefined
        ? {}
        : { nulls: nulls === "first" ? ("FIRST" as const) : ("LAST" as const) }),
    });
  }

  eq(value: Exclude<T, null> | Expression<T, K>): Predicate {
    return this.compare("=", value);
  }
  ne(value: Exclude<T, null> | Expression<T, K>): Predicate {
    return this.compare("<>", value);
  }
  /** 两个 SQL NULL 相等，只有一个 NULL 时为 false；不同于普通等号的三值逻辑。 */
  eqNullSafe(value: T | null | Expression<T | null, K>): Expression<boolean, "boolean"> {
    return this.nullSafeComparison("IS NOT DISTINCT FROM", value);
  }
  neNullSafe(value: T | null | Expression<T | null, K>): Expression<boolean, "boolean"> {
    return this.nullSafeComparison("IS DISTINCT FROM", value);
  }
  private nullSafeComparison(
    operator: "IS NOT DISTINCT FROM" | "IS DISTINCT FROM",
    value: T | null | Expression<T | null, K>,
  ): Expression<boolean, "boolean"> {
    return new Expression({
      node: freezeNode({
        kind: "binary",
        operator,
        left: this.node,
        right: operand(value, this.type.nullable()),
      }),
      type: types.boolean(),
    });
  }
  gt(value: Exclude<T, null> | Expression<T, K>): Predicate {
    return this.compare(">", value);
  }
  gte(value: Exclude<T, null> | Expression<T, K>): Predicate {
    return this.compare(">=", value);
  }
  lt(value: Exclude<T, null> | Expression<T, K>): Predicate {
    return this.compare("<", value);
  }
  lte(value: Exclude<T, null> | Expression<T, K>): Predicate {
    return this.compare("<=", value);
  }

  private compare(operator: BinaryOperator, value: Exclude<T, null> | Expression<T, K>): Predicate {
    if (value === null || value === undefined) throw new TypeError("Use isNull() for SQL NULL");
    return new Expression({
      node: freezeNode({
        kind: "binary",
        operator,
        left: this.node,
        right: operand(value, this.type),
      }),
      type: types.boolean().nullable(),
    });
  }

  isNull(): Expression<boolean, "boolean"> {
    return new Expression({
      node: freezeNode({ kind: "unary", operator: "IS NULL", operand: this.node }),
      type: types.boolean(),
    });
  }
  isNotNull(): Expression<boolean, "boolean"> {
    return new Expression({
      node: freezeNode({ kind: "unary", operator: "IS NOT NULL", operand: this.node }),
      type: types.boolean(),
    });
  }
  in(values: readonly Exclude<T, null>[]): Predicate {
    // 空集合的恒假语义固定，不生成各数据库不兼容的 IN ()。
    if (values.length === 0) return valueOf(false, types.boolean());
    return new Expression({
      node: freezeNode({
        kind: "in",
        operand: this.node,
        values: Object.freeze(values.map((value) => operand(value, this.type))),
        negated: false,
      }),
      type: types.boolean().nullable(),
    });
  }
  add(
    this: [K] extends [Numeric] ? Expression<T, K> : never,
    value: Input<T, K>,
  ): Expression<T, K> {
    return this.binary("+", value);
  }
  subtract(
    this: [K] extends [Numeric] ? Expression<T, K> : never,
    value: Input<T, K>,
  ): Expression<T, K> {
    return this.binary("-", value);
  }
  multiply(
    this: [K] extends [Numeric] ? Expression<T, K> : never,
    value: Input<T, K>,
  ): Expression<T, K> {
    return this.binary("*", value);
  }
  private binary(operator: BinaryOperator, value: Input<T, K>): Expression<T, K> {
    return new Expression({
      node: freezeNode({
        kind: "binary",
        operator,
        left: this.node,
        right: operand(value, this.type),
      }),
      type: this.type,
    });
  }
  upper(this: Expression<T, "text">): Expression<string | (null extends T ? null : never), "text"> {
    const type = this.type.acceptsNull
      ? types.text(this.type.storage.length).nullable()
      : types.text(this.type.storage.length);
    return call(
      { name: "upper", phase: "row", render: all((args) => sqlFragment`UPPER(${args[0]!})`) },
      type,
      [this],
    ) as Expression<string | (null extends T ? null : never), "text">;
  }
}

/** 参数类型由声明决定；不在执行时通过 JS typeof 重新猜测 SQL 类型。 */
export function valueOf<T, K extends SqlKind>(value: T, type: DataType<T, K>): Expression<T, K> {
  return new Expression({
    node: freezeNode({ kind: "parameter", value: type.encode(value), type }),
    type,
  });
}

/** 显式 SQL 逃生口：静态文本属于调用方代码，插值只接受表达式或标识符等结构节点。 */
export function sql(
  text: TemplateStringsArray,
  ...operands: readonly (AnyExpression | Node)[]
): Node {
  return sqlFragment(
    text,
    ...operands.map((value) => (value instanceof Expression ? value.node : value)),
  );
}
function operand<T, K extends SqlKind>(value: Input<T, K>, type: DataType<T, K>): Node {
  if (!(value instanceof Expression)) return valueOf(value, type).node;
  // 比较不选择结果 reader，因此共同文本长度/decimal 精度可以不同；业务单位及原生物理表示仍须相同。
  if (
    type.storage.kind !== value.type.storage.kind ||
    !type.hasSameCodec(value.type) ||
    (type.storage.native && !type.hasSameStorage(value.type))
  )
    throw new TypeError("Operands require compatible SQL kinds and codec identities");
  return value.node;
}
function all(render: (arguments_: readonly Node[]) => Node): FunctionDefinition["render"] {
  return Object.freeze({ postgres: render, mysql: render, hana: render });
}
export function call<T, K extends SqlKind>(
  definition: FunctionDefinition,
  type: DataType<T, K>,
  arguments_: readonly AnyExpression[],
): Expression<T, K> {
  // 元信息和实现可来自 class getter/原型方法；展开对象会遗漏它们，裸调用又会丢掉私有字段的接收者。
  const { name, phase, syntax, render: supplied } = definition;
  if (typeof name !== "string" || !name.trim() || !["row", "aggregate", "window"].includes(phase))
    throw new TypeError("A SQL function requires a name and a valid evaluation phase");
  if (syntax !== undefined && syntax !== "value" && syntax !== "predicate")
    throw new TypeError("Function syntax must be value or predicate");
  if (!supplied || (typeof supplied !== "object" && typeof supplied !== "function"))
    throw new TypeError("A SQL function requires its dialect renderers");
  const render: Partial<
    Record<keyof FunctionDefinition["render"], (arguments_: readonly Node[]) => Node>
  > = {};
  for (const dialect of ["postgres", "mysql", "hana"] as const) {
    const renderer = supplied[dialect];
    if (renderer !== undefined) {
      if (typeof renderer !== "function")
        throw new TypeError("A dialect renderer must be a function");
      render[dialect] = renderer.bind(supplied);
    }
  }
  return new Expression({
    node: freezeNode({
      kind: "call",
      definition: Object.freeze({
        name,
        phase,
        ...(syntax === undefined ? {} : { syntax }),
        render: Object.freeze(render),
      }),
      arguments: Object.freeze(arguments_.map((argument) => argument.node)),
      type,
    }),
    type,
  });
}
export function and(...predicates: readonly Predicate[]): Predicate {
  return combine("AND", predicates, true);
}
export function or(...predicates: readonly Predicate[]): Predicate {
  return combine("OR", predicates, false);
}
function combine(
  operator: "AND" | "OR",
  predicates: readonly Predicate[],
  identity: boolean,
): Predicate {
  if (!predicates.length)
    return new Expression({
      node: freezeNode({ kind: "constant", value: identity }),
      type: types.boolean(),
    });
  return predicates.slice(1).reduce(
    (left, right) =>
      new Expression({
        node: freezeNode({ kind: "binary", operator, left: left.node, right: right.node }),
        type: types.boolean().nullable(),
      }),
    predicates[0]!,
  );
}
export function not<T extends boolean | null>(
  predicate: Expression<T, "boolean">,
): Expression<boolean | (null extends T ? null : never), "boolean"> {
  if (!(predicate instanceof Expression) || predicate.type.storage.kind !== "boolean")
    throw new TypeError("NOT requires a boolean expression");
  return new Expression({
    node: freezeNode({ kind: "unary", operator: "NOT", operand: predicate.node }),
    type: predicate.type.acceptsNull ? types.boolean().nullable() : types.boolean(),
  }) as Expression<boolean | (null extends T ? null : never), "boolean">;
}
