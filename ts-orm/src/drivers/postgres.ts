import type { Pool, PoolClient } from "pg";
import type { Driver, DriverConnection, DriverResult } from "../execution/driver.js";

/**
 * 适配调用方拥有的 pg Pool，ORM 不关闭整个池。
 * 每个查询局部保留驱动文本值，由投影 codec 解码；不改 pg 的全局 parser。
 */
export function postgresDriver(pool: Pool): Driver {
  return {
    async acquire() {
      return new PostgresConnection(await pool.connect());
    },
  };
}
class PostgresConnection implements DriverConnection {
  private released = false;
  private broken: Error | undefined;
  constructor(private readonly client: PoolClient) {}
  private requireOpen(): void {
    if (this.released) throw new Error("PostgreSQL connection has been released");
  }
  async execute(sql: string, parameters: readonly unknown[]): Promise<DriverResult> {
    this.requireOpen();
    const result = await this.client.query<Record<string, unknown>>({
      text: sql,
      values: [...parameters],
      types: {
        getTypeParser: (_oid: number, format?: string) =>
          format === "binary"
            ? () => {
                throw new TypeError(
                  "The PostgreSQL adapter requires text results; configure the pool with binary: false",
                );
              }
            : (value: string) => value,
      },
    });
    if (result.rowCount !== null && !Number.isSafeInteger(result.rowCount))
      throw new RangeError("Driver returned an unsafe affected row count");
    return { rows: result.rows, affectedRows: BigInt(result.rowCount ?? 0) };
  }
  async begin(): Promise<void> {
    await this.control("BEGIN");
  }
  async commit(): Promise<void> {
    await this.control("COMMIT");
  }
  async rollback(): Promise<void> {
    await this.control("ROLLBACK");
  }
  private async control(sql: string): Promise<void> {
    this.requireOpen();
    try {
      await this.client.query(sql);
    } catch (error) {
      this.broken =
        error instanceof Error
          ? error
          : new Error("PostgreSQL transaction control failed", { cause: error });
      throw error;
    }
  }
  async release(): Promise<void> {
    if (this.released) return;
    this.released = true;
    // 提交/回滚失败的连接不放回可复用队列，避免将未知事务状态交给下一次查询。
    this.client.release(this.broken);
  }
}
