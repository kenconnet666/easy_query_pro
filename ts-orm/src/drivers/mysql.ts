import type { Pool, PoolConnection } from "mysql2/promise";
import type { ExecuteValues, ResultSetHeader, RowDataPacket } from "mysql2";
import { Buffer } from "node:buffer";
import type { Driver, DriverConnection, DriverResult } from "../execution/driver.js";

/** 使用 mysql2 服务端预处理，值不会按 NO_BACKSLASH_ESCAPES 等 SQL mode 拼进 SQL 字符串。 */
export function mysqlDriver(pool: Pool): Driver {
  return {
    async acquire() {
      return new MySqlConnection(await pool.getConnection());
    },
  };
}

function parameter(value: unknown): ExecuteValues {
  if (
    value === null ||
    typeof value === "string" ||
    typeof value === "boolean" ||
    typeof value === "bigint"
  )
    return value;
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (value instanceof Uint8Array) return Buffer.from(value);
  if (value instanceof Date && Number.isFinite(value.getTime())) return value;
  throw new TypeError(
    "MySQL bindings require scalar values, bytes or Date; encode structured values explicitly",
  );
}
class MySqlConnection implements DriverConnection {
  private released = false;
  private broken = false;
  constructor(private readonly connection: PoolConnection) {}
  private requireOpen(): void {
    if (this.released) throw new Error("MySQL connection has been released");
  }
  async execute(sql: string, parameters: readonly unknown[]): Promise<DriverResult> {
    this.requireOpen();
    try {
      const [result] = await this.connection.execute<RowDataPacket[] | ResultSetHeader>(
        {
          sql,
          rowsAsArray: false,
          supportBigNumbers: true,
          bigNumberStrings: true,
          dateStrings: true,
          // decimalNumbers 是连接级选项；局部读取精确文本，避免调用方池配置使金额先变成 number。
          typeCast(field, next) {
            if (field.type === "DECIMAL" || field.type === "NEWDECIMAL") return field.string();
            if (field.type === "JSON") return field.string("utf8");
            return next();
          },
        },
        parameters.map(parameter),
      );
      if (Array.isArray(result)) return { rows: result, affectedRows: 0n };
      if (!Number.isSafeInteger(result.affectedRows))
        throw new RangeError("Driver returned an unsafe affected row count");
      return { rows: [], affectedRows: BigInt(result.affectedRows) };
    } catch (error) {
      if (typeof error === "object" && error !== null && "fatal" in error && error.fatal === true)
        this.broken = true;
      throw error;
    }
  }
  async begin(): Promise<void> {
    await this.control(() => this.connection.beginTransaction());
  }
  async commit(): Promise<void> {
    await this.control(() => this.connection.commit());
  }
  async rollback(): Promise<void> {
    await this.control(() => this.connection.rollback());
  }
  private async control(operation: () => Promise<unknown>): Promise<void> {
    this.requireOpen();
    try {
      await operation();
    } catch (error) {
      this.broken = true;
      throw error;
    }
  }
  async release(): Promise<void> {
    if (this.released) return;
    this.released = true;
    if (this.broken) this.connection.destroy();
    else this.connection.release();
  }
}
