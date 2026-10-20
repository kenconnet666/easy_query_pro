import { DataType } from "../schema/data-type.js";
import type { NativeStorage, SqlTypeWrapper } from "../schema/native-type.js";

const uuidCast: SqlTypeWrapper = ["CAST(", " AS UUID)"];
const uuidStorage: NativeStorage = {
  identity: Symbol("pg:uuid"),
  dialects: { postgres: { parameter: uuidCast, cast: uuidCast, equality: true, ordering: true } },
};
function uuid(value: unknown): string {
  // 使用标准 8-4-4-4-12 形式，允许输入大写并规范化；不限制版本或 variant 来排除数据库合法值。
  if (
    typeof value !== "string" ||
    !/^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(value)
  )
    throw new TypeError("UUID requires the standard 8-4-4-4-12 hexadecimal form");
  return value.toLowerCase();
}

/** 原生类型与共同类型使用同一个 DataType；业务 ID 对象继续通过 .map() 挂载。 */
export const postgresTypes = {
  uuid(): DataType<string, "pg:uuid"> {
    return new DataType<string, "pg:uuid">(
      { kind: "pg:uuid", native: uuidStorage },
      { identity: "pg:uuid", encode: uuid, decode: uuid },
    );
  },
};
