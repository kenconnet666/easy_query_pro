import type { CompiledQuery } from "../compiler/select.js";
import type { Dialect } from "../dialects/dialect.js";
import type { Driver, DriverConnection, DriverResult } from "./driver.js";
import { transactionOptions, type TransactionOptions } from "./transaction.js";

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
    return this.withConnection((connection) => connection.execute(sql, parameters));
  }
  /** 多语句写入一次借用、顺序执行；已有事务连接的提交/归还仍由事务回调负责。 */
  async withConnection<T>(operation: (connection: DriverConnection) => Promise<T>): Promise<T> {
    this.assertActive();
    if (this.connection) return operation(this.connection);
    if (!this.driver)
      throw new Error("No driver is installed; this database can only compile queries");
    const connection = await this.driver.acquire();
    let failure: unknown;
    let failed = false;
    try {
      return await operation(connection);
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
  /** 显式借出一条连接；提交、回滚和释放各自的失败不能掩盖回调原始错误。 */
  async transaction<T>(
    callback: (transaction: Session) => Promise<T>,
    options?: TransactionOptions,
  ): Promise<T> {
    this.assertActive();
    if (this.connection)
      throw new Error("Nested transactions require savepoints and are not enabled");
    if (!this.driver) throw new Error("A transaction requires a driver");
    const selected = transactionOptions(options, this.driver.transactionCapabilities);
    const connection = await this.driver.acquire();
    const transactionSession = new Session(this.dialect, this.owner, this.driver, connection);
    const failures: unknown[] = [];
    let result: T | undefined;
    try {
      await connection.begin(selected);
      result = await callback(transactionSession);
      // 回调结束即关闭业务入口，不能等 COMMIT 完成才阻止逃逸的 tx 再提交 SQL。
      transactionSession.active = false;
      await connection.commit();
    } catch (error) {
      transactionSession.active = false;
      failures.push(error);
      try {
        await connection.rollback();
      } catch (rollbackError) {
        failures.push(rollbackError);
      }
    } finally {
      transactionSession.active = false;
      try {
        await connection.release();
      } catch (releaseError) {
        failures.push(releaseError);
      }
    }
    if (failures.length === 1) throw failures[0];
    if (failures.length)
      throw new AggregateError(failures, "Transaction failed during execution or cleanup");
    return result as T;
  }
}
