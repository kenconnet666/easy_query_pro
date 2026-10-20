/** SQL 类型族与 JavaScript 值类型分别保存，decimal 的 string 载体不会获得文本列的 API。 */
export type SqlKind = "integer" | "bigint" | "decimal" | "text" | "boolean" | "json";
export type CodecIdentity = string | symbol | readonly (string | symbol)[];
export interface Codec<T> {
  readonly identity: CodecIdentity;
  encode(value: T): unknown;
  decode(value: unknown): T;
}
export interface StorageType<K extends SqlKind = SqlKind> {
  readonly kind: K;
  readonly length?: number;
  readonly precision?: number;
  readonly scale?: number;
}
export interface ValueMapping<T, U> {
  readonly identity: symbol;
  to(value: T): U;
  from(value: U): T;
}

/**
 * SQL 存储描述、参数编码和结果解码的共同载体。
 * nullable 在进入业务 codec 前处理 SQL NULL，避免 Number(null) 或业务构造器吞掉空值。
 */
export class DataType<T, K extends SqlKind = SqlKind> {
  readonly storage: StorageType<K>;
  readonly codec: Codec<T>;
  readonly acceptsNull: boolean;
  constructor(
    storage: StorageType<K>,
    codec: Codec<T>,
    acceptsNull: null extends T ? boolean : false = false,
  ) {
    validateStorage(storage);
    this.storage = Object.freeze({ ...storage });
    this.codec = Object.freeze({
      identity: Array.isArray(codec.identity) ? Object.freeze([...codec.identity]) : codec.identity,
      encode: codec.encode.bind(codec),
      decode: codec.decode.bind(codec),
    });
    this.acceptsNull = acceptsNull;
    // 基类不能抢先冻结子类实例，否则扩展类的字段初始化会在 super() 之后失败。
    if (new.target === DataType) Object.freeze(this);
  }

  encode(value: T): unknown {
    if (value === undefined) throw new TypeError("undefined is omission, not a SQL value");
    if (value === null) {
      if (!this.acceptsNull) throw new TypeError("This SQL type does not allow NULL");
      return null;
    }
    return this.codec.encode(value);
  }

  decode(value: unknown): T {
    if (value === null) {
      if (!this.acceptsNull) throw new TypeError("Unexpected SQL NULL in a non-null projection");
      return null as T;
    }
    return this.codec.decode(value);
  }

  nullable(): DataType<T | null, K> {
    return new DataType<T | null, K>(this.storage, this.codec as Codec<T | null>, true);
  }

  /** 完整映射链都是读取契约的一部分，不能只比较最外层业务对象的 identity。 */
  hasSameCodec(other: AnyDataType): boolean {
    const left = codecPath(this.codec.identity);
    const right = codecPath(other.codec.identity);
    return left.length === right.length && left.every((part, index) => part === right[index]);
  }

  /** 映射复用底层 codec；数据库值先正规化，再进入业务对象转换。identity 用于 UNION 契约核查。 */
  map<V>(
    mapping: ValueMapping<V, Exclude<T, null>>,
  ): DataType<V | (null extends T ? null : never), K> {
    const source = this;
    return new DataType<V | (null extends T ? null : never), K>(
      this.storage,
      {
        identity: Object.freeze([...codecPath(source.codec.identity), mapping.identity]),
        encode: (value) => source.encode(mapping.to(value as V)),
        decode: (value) => mapping.from(source.decode(value) as Exclude<T, null>),
      },
      this.acceptsNull as null extends V | (null extends T ? null : never) ? boolean : false,
    );
  }
}

function codecPath(identity: CodecIdentity): readonly (string | symbol)[] {
  return typeof identity === "string" || typeof identity === "symbol" ? [identity] : identity;
}

function validateStorage(type: StorageType): void {
  switch (type.kind) {
    case "text":
      if (!Number.isInteger(type.length) || type.length! < 1 || type.length! > 5000)
        throw new RangeError("Common text length must be 1..5000");
      return;
    case "decimal":
      if (
        !Number.isInteger(type.precision) ||
        type.precision! < 1 ||
        type.precision! > 38 ||
        !Number.isInteger(type.scale) ||
        type.scale! < 0 ||
        type.scale! > type.precision!
      )
        throw new RangeError(
          "Common DECIMAL requires 1 <= precision <= 38 and 0 <= scale <= precision",
        );
      return;
    case "integer":
    case "bigint":
    case "boolean":
    case "json":
      return;
    default:
      throw new TypeError("Unsupported SQL storage kind");
  }
}

function integer(value: unknown): number {
  const result = typeof value === "string" && /^-?\d+$/.test(value) ? Number(value) : value;
  if (typeof result !== "number" || !Number.isSafeInteger(result))
    throw new TypeError("Expected a safe integer");
  if (result < -2147483648 || result > 2147483647)
    throw new RangeError("Value is outside signed 32-bit INTEGER");
  return result;
}
function text(value: unknown): string {
  if (typeof value !== "string") throw new TypeError("Expected a string");
  return value;
}
function int64(value: unknown): bigint {
  if (typeof value === "number" && !Number.isSafeInteger(value))
    throw new TypeError("Unsafe bigint driver value");
  if (!(
    typeof value === "bigint" ||
    typeof value === "number" ||
    (typeof value === "string" && /^-?\d+$/.test(value))
  ))
    throw new TypeError("Expected a bigint value");
  const result = BigInt(value);
  if (result < -(1n << 63n) || result >= 1n << 63n)
    throw new RangeError("Value is outside signed BIGINT");
  return result;
}
function boolean(value: unknown): boolean {
  if (value === true || value === 1 || value === "1" || value === "t" || value === "true")
    return true;
  if (value === false || value === 0 || value === "0" || value === "f" || value === "false")
    return false;
  throw new TypeError("Expected a boolean driver value");
}

/** 共同类型先保持三库可验证的值域；原生大数/特殊类型通过显式扩展挂载。 */
export const types = {
  integer(): DataType<number, "integer"> {
    return new DataType<number, "integer">(
      { kind: "integer" },
      {
        identity: "integer",
        encode(value) {
          if (typeof value !== "number")
            throw new TypeError("INTEGER parameters require number values");
          return integer(value);
        },
        decode: integer,
      },
    );
  },
  bigint(): DataType<bigint, "bigint"> {
    return new DataType<bigint, "bigint">(
      { kind: "bigint" },
      {
        identity: "bigint",
        encode(value) {
          if (typeof value !== "bigint")
            throw new TypeError("BIGINT parameters require bigint values");
          return int64(value).toString();
        },
        decode: int64,
      },
    );
  },
  text(length = 255): DataType<string, "text"> {
    return new DataType(
      { kind: "text", length },
      {
        identity: "text",
        encode(value) {
          const result = text(value);
          if (result.length > length)
            throw new RangeError("Text exceeds the declared UTF-16 length");
          return result;
        },
        decode: text,
      },
    );
  },
  decimal(precision = 19, scale = 4): DataType<string, "decimal"> {
    return new DataType(
      { kind: "decimal", precision, scale },
      {
        identity: "decimal-string",
        encode(value) {
          const result = text(value);
          const match = /^[-+]?(\d+)(?:\.(\d+))?$/.exec(result);
          if (!match) throw new TypeError("DECIMAL uses plain decimal strings");
          const integral = match[1]!.replace(/^0+/, "");
          const fractional = match[2] ?? "";
          if (integral.length > precision - scale || fractional.length > scale)
            throw new RangeError("DECIMAL value exceeds its precision or scale");
          return result;
        },
        decode(value) {
          if (typeof value !== "string")
            throw new TypeError("DECIMAL must be returned losslessly as a string");
          return value;
        },
      },
    );
  },
  boolean(): DataType<boolean, "boolean"> {
    return new DataType(
      { kind: "boolean" },
      {
        identity: "boolean",
        encode: (value) => {
          if (typeof value !== "boolean") throw new TypeError("Expected a boolean");
          return value;
        },
        decode: boolean,
      },
    );
  },
  json<T>(validate: (value: unknown) => T): DataType<T, "json"> {
    return new DataType(
      { kind: "json" },
      {
        identity: Symbol("json"),
        encode: (value) => JSON.stringify(validate(value)),
        decode: (value) =>
          validate(typeof value === "string" ? (JSON.parse(value) as unknown) : value),
      },
    );
  },
};
export type AnyDataType = DataType<unknown, SqlKind>;
export type ValueOf<D> = D extends DataType<infer T, SqlKind> ? T : never;
