import type { Codec } from "./data-type.js";

/** 只表示文档顶层的 JSON null；JS null 始终留给 SQL NULL，嵌套 JSON null 仍用普通 null。 */
export const jsonNull: unique symbol = Symbol("JSON null");
export type JsonNull = typeof jsonNull;
export type JsonDocument<T> = NonNullable<T> | (null extends T ? JsonNull : never);

type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };

/**
 * JSON.stringify 会静默忽略 undefined/函数、改变 Date，并把空洞/非有限数变成 null。
 * 先创建纯 JSON 数据快照再序列化，既拒绝这些损失，也不执行用户对象的 getter/toJSON。
 * 只跟踪当前祖先链；同一普通对象出现在两个分支不等于循环引用。
 */
function snapshot(value: unknown, ancestors = new Set<object>(), path = "$"): JsonValue {
  if (value === null || typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "number") {
    if (!Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value)))
      throw new TypeError(
        `JSON number at ${path} must be finite and use a safe integer; use jsonText for exact numeric text`,
      );
    return value;
  }
  if (typeof value !== "object") throw new TypeError(`Unsupported JSON value at ${path}`);
  if (ancestors.has(value)) throw new TypeError(`Circular JSON value at ${path}`);
  const array = Array.isArray(value);
  if (
    !array &&
    Object.getPrototypeOf(value) !== Object.prototype &&
    Object.getPrototypeOf(value) !== null
  )
    throw new TypeError(
      `JSON objects at ${path} must be plain data; map custom classes explicitly`,
    );
  ancestors.add(value);
  try {
    const descriptors = Object.getOwnPropertyDescriptors(value) as Record<
      PropertyKey,
      PropertyDescriptor
    >;
    if (array) {
      for (const key of Reflect.ownKeys(descriptors)) {
        if (!descriptors[key]!.enumerable) continue;
        if (typeof key !== "string" || !/^(0|[1-9]\d*)$/.test(key) || Number(key) >= value.length)
          throw new TypeError(
            `JSON arrays cannot have additional enumerable properties at ${path}`,
          );
      }
      return Array.from({ length: value.length }, (_, index) => {
        const descriptor = descriptors[String(index)];
        if (!descriptor || !("value" in descriptor))
          throw new TypeError(`JSON arrays cannot contain holes or accessors at ${path}[${index}]`);
        return snapshot(descriptor.value, ancestors, `${path}[${index}]`);
      });
    }
    const result: { [key: string]: JsonValue } = Object.create(null) as {
      [key: string]: JsonValue;
    };
    for (const key of Reflect.ownKeys(descriptors)) {
      const descriptor = descriptors[key]!;
      if (!descriptor.enumerable) continue;
      if (typeof key !== "string" || !("value" in descriptor))
        throw new TypeError(
          `JSON objects cannot contain enumerable symbols or accessors at ${path}`,
        );
      result[key] = snapshot(descriptor.value, ancestors, `${path}[${JSON.stringify(key)}]`);
    }
    return result;
  } finally {
    ancestors.delete(value);
  }
}

function parseText(value: unknown): unknown {
  if (typeof value !== "string")
    throw new TypeError("JSON decoding requires raw driver text, not a pre-parsed value");
  try {
    return JSON.parse(value) as unknown;
  } catch (cause) {
    throw new TypeError("Invalid JSON text", { cause });
  }
}

export function jsonCodec<T>(validate: (value: unknown) => T): Codec<JsonDocument<T>> {
  if (typeof validate !== "function") throw new TypeError("JSON requires a validator function");
  return {
    identity: Symbol("json-document"),
    encode(value) {
      const validated = validate(value === jsonNull ? null : value);
      return JSON.stringify(snapshot(validated));
    },
    decode(value) {
      const parsed = parseText(value);
      // 先检查解析值，再交给业务 validator，避免 validator 把已失真的大整数藏到字符串/对象中。
      snapshot(parsed);
      const validated = validate(parsed);
      snapshot(validated);
      return (validated === null ? jsonNull : validated) as JsonDocument<T>;
    },
  };
}

/** 只校验 JSON 语法并返回原文本；校验阶段的 JS number 不参与返回值或再次序列化。 */
export const jsonTextCodec: Codec<string> = {
  identity: "json-text",
  encode(value) {
    parseText(value);
    return value;
  },
  decode(value) {
    parseText(value);
    return value as string;
  },
};
