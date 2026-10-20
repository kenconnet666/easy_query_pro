import type { Connection, ConnectionPool, HanaParameterType } from "@sap/hana-client";
import { Buffer } from "node:buffer";
import type { Driver, DriverConnection, DriverResult } from "../execution/driver.js";
import {
  isolationSql,
  standardTransactionCapabilities,
  type TransactionOptions,
} from "../execution/transaction.js";

/**
 * 适配应用拥有的显式 HANA 连接池。disconnect 将租约交回原池，池的关闭仍由应用负责。
 * SDK 的 exec 完整获取结果并清理内部 statement，不把需要连接继续存活的 ResultSet 暴露给 codec。
 * 使用共同日期类型时，应用池应配置 emptyTimestampIsNull:false，避免 SDK 把非 NULL EMPTY 值先变成 null。
 */
export function hanaDriver(pool: ConnectionPool): Driver {
  return {
    // READ ONLY 在 HANA 跨事务保留，SDK 显式池不复原，也没有可靠的单租约 discard API。
    // 先拒绝这项配置，不能把共享池的访问模式改变后假称事务局部设置。
    transactionCapabilities: Object.freeze({ ...standardTransactionCapabilities, readOnly: false }),
    async acquire() {
      const connection = await new Promise<Connection>((resolve, reject) => {
        pool.getConnection((error, value) => {
          if (error) reject(error);
          else if (!value) reject(new Error("HANA pool returned no connection"));
          else resolve(value);
        });
      });
      return new HanaConnection(connection);
    },
  };
}

function parameter(value: unknown): HanaParameterType {
  if (value === null || typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "bigint") return value.toString();
  if (value instanceof Uint8Array) return Buffer.from(value);
  throw new TypeError(
    "HANA bindings require scalar values or bytes; encode structured values explicitly",
  );
}

class HanaConnection implements DriverConnection {
  private released = false;
  constructor(private readonly connection: Connection) {}
  private requireOpen(): void {
    if (this.released) throw new Error("HANA connection has been released");
  }
  async execute(sql: string, parameters: readonly unknown[]): Promise<DriverResult> {
    this.requireOpen();
    const result = await new Promise<unknown>((resolve, reject) => {
      this.connection.exec<unknown>(
        sql,
        parameters.map(parameter),
        { rowsAsArray: false },
        (error, rows) => {
          if (error) reject(error);
          else resolve(rows);
        },
      );
    });
    if (Array.isArray(result)) {
      if (!result.every((row) => typeof row === "object" && row !== null && !Array.isArray(row)))
        throw new TypeError("HANA adapter expects one result set with object rows");
      return { rows: result as Readonly<Record<string, unknown>>[], affectedRows: 0n };
    }
    if (result === undefined || result === null) return { rows: [], affectedRows: 0n };
    if (typeof result !== "number" || !Number.isSafeInteger(result))
      throw new TypeError("HANA returned an unsupported result or unsafe affected row count");
    return { rows: [], affectedRows: BigInt(result) };
  }
  async begin(options: TransactionOptions = {}): Promise<void> {
    this.requireOpen();
    this.connection.setAutoCommit(false);
    if (options.isolationLevel !== undefined)
      await this.execute(
        "SET TRANSACTION ISOLATION LEVEL " + isolationSql[options.isolationLevel],
        [],
      );
  }
  async commit(): Promise<void> {
    this.requireOpen();
    await new Promise<void>((resolve, reject) =>
      this.connection.commit((error) => (error ? reject(error) : resolve())),
    );
  }
  async rollback(): Promise<void> {
    this.requireOpen();
    await new Promise<void>((resolve, reject) =>
      this.connection.rollback((error) => (error ? reject(error) : resolve())),
    );
  }
  async release(): Promise<void> {
    if (this.released) return;
    this.released = true;
    // SDK 返回显式池时回滚并重置隔离级别；不调用 setAutoCommit(true) 隐式提交失败事务。
    await new Promise<void>((resolve, reject) =>
      this.connection.disconnect((error) => (error ? reject(error) : resolve())),
    );
  }
}
