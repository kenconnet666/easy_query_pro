import type { StorageType } from "../schema/data-type.js";
import { validateName } from "../schema/table.js";
import type { DialectName } from "../expression/node.js";
import { requireNativeSupport } from "../schema/native-type.js";

/** 方言仅负责 SQL 能力和语法，连接与结果读取属于 driver/codec。 */
export interface Dialect {
  readonly name: DialectName;
  quote(identifier: string): string;
  parameter(index: number, type: StorageType): string;
  cast(expression: string, type: StorageType): string;
  pagination(limit: number | undefined, offset: number): string;
}
function castType(name: DialectName, type: StorageType): string {
  switch (type.kind) {
    case "integer":
      return name === "mysql" ? "SIGNED" : "INTEGER";
    case "bigint":
      return name === "mysql" ? "SIGNED" : "BIGINT";
    case "decimal":
      return `DECIMAL(${type.precision},${type.scale})`;
    case "text":
      return name === "hana"
        ? `NVARCHAR(${type.length})`
        : name === "mysql"
          ? `CHAR(${type.length})`
          : `VARCHAR(${type.length})`;
    case "boolean":
      return name === "mysql" ? "SIGNED" : "BOOLEAN";
    case "json":
      return name === "postgres" ? "JSONB" : name === "mysql" ? "JSON" : "NCLOB";
    case "date":
      return "DATE";
    case "localDateTime":
      return name === "mysql" ? "DATETIME(6)" : name === "hana" ? "TIMESTAMP" : "TIMESTAMP(6)";
    default:
      throw new TypeError("A native type must use its declared SQL rendering");
  }
}
export function dialect(name: DialectName): Dialect {
  return Object.freeze({
    name,
    quote(identifier: string) {
      validateName(identifier);
      const mark = name === "mysql" ? "`" : '"';
      return mark + identifier.replaceAll(mark, mark + mark) + mark;
    },
    parameter(index: number, type: StorageType) {
      const native = requireNativeSupport(type, name);
      const placeholder = name === "postgres" ? "$" + index : "?";
      if (native) return native.parameter[0] + placeholder + native.parameter[1];
      // 输入已由 codec 正规化，显式格式不受 HANA 会话 DATE_FORMAT/TIMESTAMP_FORMAT 的影响。
      if (name === "hana" && type.kind === "date") return "TO_DATE(?, 'YYYY-MM-DD')";
      if (name === "hana" && type.kind === "localDateTime")
        return "TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS.FF6')";
      // MySQL 的 CHAR CAST 会提升参数的 collation 强度，与列的非同名 collation 冲突。
      // 裸文本参数保留可强制转换性，由比较列决定 collation；长度已由 codec 校验。
      if (name === "mysql" && type.kind === "text") return "?";
      // 独立参数也有明确类型，避免 PG 的两个 unknown 参数无法选择算术重载。
      return `CAST(${placeholder} AS ${castType(name, type)})`;
    },
    cast(expression: string, type: StorageType) {
      const native = requireNativeSupport(type, name);
      if (native) {
        if (!native.cast)
          throw new TypeError(`SQL type ${type.kind} does not support CAST on ${name}`);
        return native.cast[0] + expression + native.cast[1];
      }
      return `CAST(${expression} AS ${castType(name, type)})`;
    },
    pagination(limit: number | undefined, offset: number) {
      if (limit === undefined) {
        if (offset === 0) return "";
        if (name === "postgres") return ` OFFSET ${offset}`;
        throw new TypeError("An offset requires an explicit limit for this dialect");
      }
      return ` LIMIT ${limit}${offset ? " OFFSET " + offset : ""}`;
    },
  });
}
export const postgresDialect = dialect("postgres");
export const mysqlDialect = dialect("mysql");
export const hanaDialect = dialect("hana");
