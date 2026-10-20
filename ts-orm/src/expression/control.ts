import { Expression, type Predicate } from "./expression.js";
import { freezeNode } from "./node.js";
import { DataType, type AnyDataType, type SqlKind } from "../schema/data-type.js";

function compatible(left: AnyDataType, right: AnyDataType): void {
  if (!left.hasSameStorage(right) || !left.hasSameCodec(right))
    throw new TypeError("Conditional operands require matching SQL storage and codec identity");
}
function resultType<T, K extends SqlKind>(
  source: DataType<unknown, K>,
  nullable: boolean,
): DataType<T, K> {
  // 返回泛型由下面各操作的 NULL 规则推导；业务 codec 只处理相同值域的非空分支。
  return new DataType(source.storage, source.codec, nullable) as DataType<T, K>;
}

/** FALSE 和 UNKNOWN 都选 ELSE，WHEN 使用条件语法，而非 BOOLEAN 标量字符串拼接。 */
export function caseWhen<T, K extends SqlKind, U extends T | null>(
  condition: Predicate,
  whenTrue: Expression<T, K>,
  whenFalse: Expression<U, NoInfer<K>>,
): Expression<T | U, K> {
  compatible(whenTrue.type, whenFalse.type);
  return new Expression({
    node: freezeNode({
      kind: "case",
      condition: condition.node,
      whenTrue: whenTrue.node,
      whenFalse: whenFalse.node,
    }),
    type: resultType(whenTrue.type, whenTrue.type.acceptsNull || whenFalse.type.acceptsNull),
  });
}

export function coalesce<T, K extends SqlKind, U extends T | null>(
  left: Expression<T, K>,
  right: Expression<U, NoInfer<K>>,
): Expression<
  Exclude<T | U, null> | (null extends T ? (null extends U ? null : never) : never),
  K
> {
  compatible(left.type, right.type);
  return new Expression({
    node: freezeNode({ kind: "coalesce", left: left.node, right: right.node }),
    type: resultType(left.type, left.type.acceptsNull && right.type.acceptsNull),
  });
}

export function nullIf<T, K extends SqlKind>(
  left: Expression<T, K>,
  right: Expression<NoInfer<T> | null, NoInfer<K>>,
): Expression<T | null, K> {
  compatible(left.type, right.type);
  return new Expression({
    node: freezeNode({ kind: "nullif", left: left.node, right: right.node }),
    type: left.type.nullable(),
  });
}

/** 原生 CAST 可按数据库规则舍入、报错或产生 NULL；非 NULL 输入不能据此承诺非空结果。 */
export function nativeCast<V, T, K extends SqlKind>(
  value: Expression<V, SqlKind>,
  type: DataType<T, K>,
): Expression<T | null, K> {
  return new Expression({
    node: freezeNode({ kind: "cast", operand: value.node, type }),
    type: type.nullable(),
  });
}
