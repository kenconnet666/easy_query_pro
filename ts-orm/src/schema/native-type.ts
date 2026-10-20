import type { DialectName } from "../expression/node.js";
import type { StorageType } from "./data-type.js";

/** 可信类型语法位于一个操作数两侧；操作数只出现一次，不复制或吞掉参数占位符。 */
export type SqlTypeWrapper = readonly [before: string, after: string];
export interface NativeTypeDialect {
  readonly parameter: SqlTypeWrapper;
  readonly cast?: SqlTypeWrapper;
  readonly equality?: boolean;
  readonly ordering?: boolean;
}
export interface NativeStorage {
  /** 同一配置的工厂复用 identity；不同物理表示不能仅凭相同 kind 宣称兼容。 */
  readonly identity: symbol;
  readonly dialects: Readonly<Partial<Record<DialectName, NativeTypeDialect>>>;
}

const names = ["postgres", "mysql", "hana"] as const;
function wrapper(value: SqlTypeWrapper): SqlTypeWrapper {
  if (
    !Array.isArray(value) ||
    value.length !== 2 ||
    typeof value[0] !== "string" ||
    typeof value[1] !== "string"
  )
    throw new TypeError("Native SQL type wrappers require exactly two string fragments");
  return Object.freeze([value[0], value[1]]);
}

export function snapshotNativeStorage(storage: NativeStorage): NativeStorage {
  const { identity, dialects: supplied } = storage;
  if (typeof identity !== "symbol")
    throw new TypeError("Native storage requires a symbol identity");
  const dialects: Partial<Record<DialectName, NativeTypeDialect>> = {};
  for (const name of Reflect.ownKeys(supplied)) {
    if (!names.includes(name as DialectName))
      throw new TypeError("Unknown native type dialect: " + String(name));
    const entry = supplied[name as DialectName];
    if (!entry) throw new TypeError("A declared native dialect needs its type definition");
    const { parameter, cast, equality = false, ordering = false } = entry;
    if (typeof equality !== "boolean" || typeof ordering !== "boolean")
      throw new TypeError("Native comparison capabilities must be booleans");
    dialects[name as DialectName] = Object.freeze({
      parameter: wrapper(parameter),
      ...(cast === undefined ? {} : { cast: wrapper(cast) }),
      equality,
      ordering,
    });
  }
  if (!Object.keys(dialects).length)
    throw new TypeError("A native type needs at least one dialect");
  return Object.freeze({ identity, dialects: Object.freeze(dialects) });
}

function sameWrapper(left: SqlTypeWrapper | undefined, right: SqlTypeWrapper | undefined): boolean {
  return left?.[0] === right?.[0] && left?.[1] === right?.[1];
}
export function sameNativeStorage(
  left: NativeStorage | undefined,
  right: NativeStorage | undefined,
): boolean {
  if (!left || !right) return left === right;
  return (
    left.identity === right.identity &&
    names.every((name) => {
      const a = left.dialects[name];
      const b = right.dialects[name];
      return (
        sameWrapper(a?.parameter, b?.parameter) &&
        sameWrapper(a?.cast, b?.cast) &&
        a?.equality === b?.equality &&
        a?.ordering === b?.ordering
      );
    })
  );
}

export function requireNativeSupport(
  type: StorageType,
  dialect: DialectName,
): NativeTypeDialect | undefined {
  if (!type.native) return undefined;
  const definition = type.native.dialects[dialect];
  if (!definition) throw new TypeError(`SQL type ${type.kind} does not support ${dialect}`);
  return definition;
}

export function requireNativeOperation(
  type: StorageType,
  dialect: DialectName,
  operation: "equality" | "ordering",
): void {
  const definition = requireNativeSupport(type, dialect);
  if (definition && !definition[operation])
    throw new TypeError(`SQL type ${type.kind} does not support ${operation} on ${dialect}`);
}
