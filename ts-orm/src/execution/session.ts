import type { CompiledQuery } from "../compiler/select.js";
import type { Dialect } from "../dialects/dialect.js";
import type { Driver, DriverConnection, DriverResult } from "./driver.js";

/** 事务会话固定连接并显式失效，不依赖全局变量或隐式异步上下文。 */
export class Session {
  active = true;
  constructor(
    readonly dialect: Dialect,
    readonly owner: symbol,
    readonly driver: Driver | undefined,
    readonly connection?: DriverConnection,
  ) {}
  assertActive(): void {
    if (!this.active) throw new Error("This transaction has already ended");
  }
  async execute<T>(query: CompiledQuery<T>): Promise<T[]> {
    const result = await this.raw(query.sql, query.parameters);
    return result.rows.map((row) => query.decode(row));
  }
  async raw(sql: string, parameters: readonly unknown[]): Promise<DriverResult> {
    this.assertActive();
    if (this.connection) return this.connection.execute(sql, parameters);
    if (!this.driver)
      throw new Error("No driver is installed; this database can only compile queries");
    const connection = await this.driver.acquire();
    let failure: unknown;
    let failed = false;
    try {
      return await connection.execute(sql, parameters);
    } catch (error) {
      failed = true;
      failure = error;
      throw error;
    } finally {
      try {
        await connection.release();
      } catch (releaseError) {
        if (failed)
          throw new AggregateError(
            [failure, releaseError],
            "Query and connection release both failed",
          );
        throw releaseError;
      }
    }
  }
}
