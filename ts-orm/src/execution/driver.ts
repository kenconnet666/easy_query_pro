export interface DriverResult {
  readonly rows: readonly Readonly<Record<string, unknown>>[];
  /** 写入影响行数；SELECT 的返回行数由 rows 表示，此值为 0。 */
  readonly affectedRows: bigint;
}
/** 一个已借出的物理连接。事务与 release 的实现由适配器负责，方言不持有连接。 */
export interface DriverConnection {
  execute(sql: string, parameters: readonly unknown[]): Promise<DriverResult>;
  begin(): Promise<void>;
  commit(): Promise<void>;
  rollback(): Promise<void>;
  release(): Promise<void>;
}
export interface Driver {
  acquire(): Promise<DriverConnection>;
}
