import { parse } from "postgres-array";
import {
  call,
  Expression,
  valueOf,
  type ExpressionBinding,
  type Predicate,
} from "../expression/expression.js";
import { sqlFragment, type Node } from "../expression/node.js";
import { DataType, types, type CommonSqlKind } from "../schema/data-type.js";
import {
  requireNativeOperation,
  type NativeStorage,
  type SqlTypeWrapper,
} from "../schema/native-type.js";

export type PgArrayElementKind = Exclude<CommonSqlKind, "json"> | "pg:uuid";
export type PgArrayKind<K extends PgArrayElementKind = PgArrayElementKind> = K extends "pg:uuid"
  ? "pg:array.uuid"
  : `pg:array.${K}`;
const arrayStorage = Symbol("postgres-array-storage");
const arrayCodec = Symbol("postgres-array-codec");

/**
 * 依赖解析器面向可信 PG wire 文本；先检查一维结构与引号闭合，避免错误列映射输入导致解析器倒退。
 * 此处不反转义或解释元素，成熟解析器仍负责实际读取。
 */
function requireArrayText(value: unknown): asserts value is string {
  if (typeof value !== "string" || !value.startsWith("{") || !value.endsWith("}"))
    throw new TypeError(
      "PostgreSQL arrays require one-dimensional wire text with default lower bounds",
    );
  if (value === "{}") return;
  let state: "start" | "bare" | "quoted" | "closed" = "start";
  for (let i = 1; i < value.length - 1; i++) {
    const char = value[i]!;
    if (state === "quoted") {
      if (char === "\\") {
        if (++i >= value.length - 1) throw new TypeError("Unclosed PostgreSQL array escape");
      } else if (char === '"') state = "closed";
      continue;
    }
    if (char === "{" || char === "}")
      throw new TypeError("Multidimensional PostgreSQL arrays need a different value contract");
    if (char === ",") {
      if (state === "start") throw new TypeError("PostgreSQL array elements cannot be omitted");
      state = "start";
      continue;
    }
    if (state === "closed") throw new TypeError("Expected a PostgreSQL array delimiter");
    if (char === '"') {
      if (state !== "start") throw new TypeError("Unexpected PostgreSQL array quote");
      state = "quoted";
    } else {
      // PG scanner_isspace 仅识别六种 ASCII 空白；JS 的 \s 会错拒合法的 NBSP/EM SPACE 等元素。
      if (char === "\\" || /[ \t\n\r\v\f]/.test(char))
        throw new TypeError("PostgreSQL array special text must be quoted");
      state = "bare";
    }
  }
  if (state === "quoted" || state === "start")
    throw new TypeError("Unclosed or omitted PostgreSQL array element");
}

/** 上层已选择受控的元素类型名；数组 codec 保留完整元素链及其 NULL 合同。 */
export function createArrayType<T, K extends PgArrayElementKind>(
  element: DataType<T, K>,
  sqlType: string,
): DataType<readonly T[], PgArrayKind<K>> {
  const cast: SqlTypeWrapper = ["CAST(", ` AS ${sqlType}[])`];
  const native: NativeStorage = {
    identity: arrayStorage,
    dialects: { postgres: { parameter: cast, cast, equality: true, ordering: true } },
  };
  const kind = (
    element.storage.kind === "pg:uuid" ? "pg:array.uuid" : "pg:array." + element.storage.kind
  ) as PgArrayKind<K>;
  const identity =
    typeof element.codec.identity === "string" || typeof element.codec.identity === "symbol"
      ? [element.codec.identity]
      : element.codec.identity;
  return new DataType<readonly T[], PgArrayKind<K>>(
    { kind, native },
    {
      identity: [
        arrayCodec,
        ...identity,
        element.acceptsNull ? "nullable-elements" : "non-null-elements",
      ],
      encode(value) {
        if (!Array.isArray(value))
          throw new TypeError("PostgreSQL array parameters require arrays");
        // Array.from 会访问空洞，使 undefined 经过元素 codec 拒绝；保留编码副本而非调用方可变数组。
        return Object.freeze(
          Array.from(value, (item, index) => {
            try {
              const encoded = element.encode(item);
              if (
                encoded !== null &&
                typeof encoded !== "string" &&
                typeof encoded !== "boolean" &&
                typeof encoded !== "bigint" &&
                !(typeof encoded === "number" && Number.isFinite(encoded))
              )
                throw new TypeError("Array elements must encode to scalar values");
              return encoded;
            } catch (cause) {
              throw new TypeError(`Cannot encode PostgreSQL array element at index ${index}`, {
                cause,
              });
            }
          }),
        );
      },
      decode(value) {
        requireArrayText(value);
        // parser 的声明未表达 NULL/多维返回；不能让 transform 跳过 NULL 元素的校验。
        const raw: unknown = parse(value);
        if (!Array.isArray(raw) || raw.some(Array.isArray))
          throw new TypeError("Expected a one-dimensional PostgreSQL array");
        return Object.freeze(
          raw.map((item, index) => {
            try {
              return element.decode(item);
            } catch (cause) {
              throw new TypeError(`Cannot decode PostgreSQL array element at index ${index}`, {
                cause,
              });
            }
          }),
        );
      },
    },
  );
}

type ArrayValue = readonly unknown[] | null;
type ElementOf<A extends ArrayValue> = Exclude<A, null>[number];
type ArrayKind = `pg:array.${string}`;

/** 显式挂载后获得原生数组操作；查询来源、codec 和 NULL 信息仍由原 ExpressionBinding 持有。 */
export class PgArrayExpression<A extends ArrayValue, K extends ArrayKind> extends Expression<A, K> {
  constructor(binding: ExpressionBinding<A, K>) {
    super(binding);
    if (
      !binding.type.storage.kind.startsWith("pg:array.") ||
      !binding.type.storage.native?.dialects.postgres
    )
      throw new TypeError("pgArray requires a PostgreSQL array storage definition");
    const identity = binding.type.codec.identity;
    if (
      !Array.isArray(identity) ||
      identity[0] !== arrayCodec ||
      !["nullable-elements", "non-null-elements"].includes(String(identity.at(-1)))
    )
      throw new TypeError(
        "pgArray requires the array factory codec; map element types instead of the whole array",
      );
  }

  contains(value: Exclude<A, null> | Expression<A | null, K>): Predicate {
    return this.compareArray("@>", value);
  }
  overlaps(value: Exclude<A, null> | Expression<A | null, K>): Predicate {
    return this.compareArray("&&", value);
  }

  private compareArray(
    operator: "@>" | "&&",
    value: Exclude<A, null> | Expression<A | null, K>,
  ): Predicate {
    requireNativeOperation(this.type.storage, "postgres", "equality");
    let other;
    if (value instanceof Expression) {
      if (!this.type.hasSameStorage(value.type) || !this.type.hasSameCodec(value.type))
        throw new TypeError("Array operands require matching storage and element codecs");
      other = value;
    } else {
      if (!Array.isArray(value))
        throw new TypeError("Use a typed expression for a SQL NULL array operand");
      other = valueOf(value, this.type);
    }
    const render = (args: readonly Node[]) =>
      operator === "@>"
        ? sqlFragment`${args[0]!} @> ${args[1]!}`
        : sqlFragment`${args[0]!} && ${args[1]!}`;
    return call(
      {
        name: "postgres-array-" + operator,
        phase: "row",
        syntax: "predicate",
        render: { postgres: render },
      },
      types.boolean().nullable(),
      [this, other],
    );
  }

  /** 只接收业务值；绑定单元素数组再取 [1]，复用完整元素 codec，不猜元素的 SQL 模板。 */
  anyEquals(value: Exclude<ElementOf<A>, null>): Predicate {
    if (value === null || value === undefined || value instanceof Expression)
      throw new TypeError("anyEquals requires a non-null business value, not a scalar expression");
    requireNativeOperation(this.type.storage, "postgres", "equality");
    // 构造器限定为允许任意长度列表的工厂 codec；元素业务映射仍逐项执行。
    const single = valueOf([value] as unknown as A, this.type);
    return call(
      {
        name: "postgres-any-equals",
        phase: "row",
        syntax: "predicate",
        render: { postgres: (args) => sqlFragment`(${args[1]!})[1] = ANY(${args[0]!})` },
      },
      types.boolean().nullable(),
      [this, single],
    );
  }

  cardinality(): Expression<number | (null extends A ? null : never), "integer"> {
    const type = this.type.acceptsNull ? types.integer().nullable() : types.integer();
    return call(
      {
        name: "postgres-cardinality",
        phase: "row",
        render: { postgres: (args) => sqlFragment`CARDINALITY(${args[0]!})` },
      },
      type,
      [this],
    ) as Expression<number | (null extends A ? null : never), "integer">;
  }
}
export function pgArray<A extends ArrayValue, K extends ArrayKind>(
  binding: ExpressionBinding<A, K>,
): PgArrayExpression<A, K> {
  return new PgArrayExpression(binding);
}
