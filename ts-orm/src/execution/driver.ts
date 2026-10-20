import type { TransactionCapabilities, TransactionOptions } from "./transaction.js";

export interface DriverResult {
  readonly rows: readonly Readonly<Record<string, unknown>>[];
  /** 写入影响行数；SELECT 的返回行数由 rows 表示，此值为 0。 */
  readonly affectedRows: bigint;
}
/** 一个已借出的物理连接。事务与 release 的实现由适配器负责，方言不持有连接。 */
export interface DriverConnection {
  execute(sql: string, parameters: readonly unknown[]): Promise<DriverResult>;
  /** options 已由 Session 验证；控制语句必须在同一租约、首条业务 SQL 之前完成。 */
  begin(options?: TransactionOptions): Promise<void>;
  commit(): Promise<void>;
  rollback(): Promise<void>;
  release(): Promise<void>;
}
export interface Driver {
  readonly transactionCapabilities?: TransactionCapabilities;
  acquire(): Promise<DriverConnection>;
}
