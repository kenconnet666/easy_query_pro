/** 三库共同的隔离级别；它们控制数据库快照，不重放有应用副作用的业务回调。 */
export type IsolationLevel = "readCommitted" | "repeatableRead" | "serializable";

export interface TransactionOptions {
  readonly isolationLevel?: IsolationLevel;
  readonly readOnly?: boolean;
}

/** 自定义适配器必须显式声明选项支持，避免旧 begin() 实现静默忽略新选项。 */
export interface TransactionCapabilities {
  readonly isolationLevels: readonly IsolationLevel[];
  readonly readOnly: boolean;
}

export const isolationSql: Readonly<Record<IsolationLevel, string>> = Object.freeze({
  readCommitted: "READ COMMITTED",
  repeatableRead: "REPEATABLE READ",
  serializable: "SERIALIZABLE",
});

export const standardTransactionCapabilities: TransactionCapabilities = Object.freeze({
  isolationLevels: Object.freeze(Object.keys(isolationSql) as IsolationLevel[]),
  readOnly: true,
});

/** 在借连接前取快照；调用方修改 options 或 getter 不能改变已经开始的事务。 */
export function transactionOptions(
  options: TransactionOptions | undefined,
  capabilities: TransactionCapabilities | undefined,
): TransactionOptions {
  if (options === undefined) return Object.freeze({});
  if (typeof options !== "object" || options === null || Array.isArray(options))
    throw new TypeError("Transaction options must be an object");
  for (const key of Reflect.ownKeys(options))
    if (key !== "isolationLevel" && key !== "readOnly")
      throw new TypeError("Unknown transaction option: " + String(key));
  const { isolationLevel, readOnly } = options;
  if (isolationLevel !== undefined && !Object.hasOwn(isolationSql, isolationLevel))
    throw new TypeError("Invalid transaction isolation level: " + String(isolationLevel));
  if (readOnly !== undefined && typeof readOnly !== "boolean")
    throw new TypeError("Transaction readOnly must be a boolean");
  if (isolationLevel !== undefined && !capabilities?.isolationLevels.includes(isolationLevel))
    throw new TypeError("This driver does not support the requested transaction isolation level");
  if (readOnly !== undefined && !capabilities?.readOnly)
    throw new TypeError("This driver does not support the transaction readOnly option");
  return Object.freeze({
    ...(isolationLevel === undefined ? {} : { isolationLevel }),
    ...(readOnly === undefined ? {} : { readOnly }),
  });
}
