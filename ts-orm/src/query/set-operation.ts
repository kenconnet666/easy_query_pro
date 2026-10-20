import type { Expression } from "../expression/expression.js";
import { combineNumericOrigin } from "../expression/node.js";
import { DerivedSource, type QueryNode, type Selection } from "./model.js";

type Value<E> = E extends Expression<infer T, infer _K> ? T : never;
type Kind<E> = E extends Expression<infer _T, infer K> ? K : never;
type Equal<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
type MatchingSlots<L extends Selection, R extends Selection> = {
  [K in keyof L]: Equal<
    Exclude<Value<L[K]>, null>,
    Exclude<Value<R[K & keyof R]>, null>
  > extends true
    ? Equal<Kind<L[K]>, Kind<R[K & keyof R]>>
    : false;
}[keyof L];

/** 静态检查字段集合与值域，运行时继续检查字段顺序、SQL 存储和 codec 身份。 */
export type CompatibleSelection<L extends Selection, R extends Selection> =
  Equal<keyof L, keyof R> extends true
    ? false extends MatchingSlots<L, R>
      ? never
      : unknown
    : never;
export type SetSelection<L extends Selection, R extends Selection> = {
  [K in keyof L]: Expression<Value<L[K]> | Value<R[K & keyof R]>, Kind<L[K]>>;
};

export function setSource<L extends Selection, R extends Selection>(
  left: QueryNode,
  right: QueryNode,
  all: boolean,
): DerivedSource<SetSelection<L, R>> {
  if (left.owner !== right.owner) throw new TypeError("UNION cannot cross database clients");
  if (!left.projection.length || left.projection.length !== right.projection.length)
    throw new TypeError("UNION requires selected projections with the same number of columns");
  const projection = left.projection.map((slot, index) => {
    const other = right.projection[index]!;
    if (slot.key !== other.key)
      throw new TypeError(
        `UNION projection order differs at slot ${index}: ${slot.key} / ${other.key}`,
      );
    if (!slot.type.hasSameStorage(other.type) || !slot.type.hasSameCodec(other.type))
      throw new TypeError(
        `UNION projection "${slot.key}" requires matching SQL storage and codec identity`,
      );
    // 可空性只做并集：任一分支可能产出 NULL，结果就必须在业务 decoder 之前保留它。
    return Object.freeze({
      ...slot,
      type: other.type.acceptsNull ? slot.type.nullable() : slot.type,
      numericOrigin: combineNumericOrigin(slot.numericOrigin, other.numericOrigin),
    });
  });
  return new DerivedSource(
    Object.freeze({
      kind: "set",
      owner: left.owner,
      left,
      right,
      all,
      projection: Object.freeze(projection),
    }),
  );
}
