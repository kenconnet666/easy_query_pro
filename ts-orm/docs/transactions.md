# 分页、事务与连接所有权

`page(pageNumber, pageSize)` 从 1 开始，返回 `{ pageNumber, pageSize, total, totalPages, rows }`。总数和总页数使用 bigint，结果行沿用 select 的具体类型及 codec。

```ts
const page = await db.transaction(
  (tx) =>
    tx
      .from({ entry: entries })
      .where(({ entry }) => entry.enabled.eq(true))
      .orderBy(({ entry }) => entry.id.asc())
      .select(({ entry }) => ({ id: entry.id, amount: entry.amount }))
      .page(2, 20),
  { isolationLevel: "repeatableRead" },
);
```

分页需要显式 select 和 orderBy。排序中应包含唯一的最终排序键；ORM 不猜主键，也不改变排名或相等行的业务含义。已有 limit/offset 的 query 不能直接 page，需要先 as()/CTE 明确已有范围，再从新来源分页。页码、大小与计算后的 offset 都检查安全整数范围。

COUNT 包装完整查询，保留 DISTINCT、分组、HAVING、窗口、CTE 和集合运算，不删掉投影来猜总数。COUNT 和当前页都先完成编译，再借用同一连接顺序执行。空结果或越界页仍查询实际数据，不凭前一条 COUNT 擅自跳过第二条查询。

**同一个连接不等于同一快照。** READ COMMITTED 允许 COUNT 后另一事务提交的数据出现在当前页；需要同一视图时显式使用 REPEATABLE READ 或 SERIALIZABLE。后者可能加锁或报告并发冲突。ORM 不自动重试回调，因为发送消息、调用外部服务等应用副作用不能安全重放。分页跨多个独立事务时仍可能发生位移，稳定游标分页是另一个待实现能力。

## 事务选项

```ts
await db.transaction(
  async (tx) => {
    await tx.insert(entries).values(input).execute();
    return tx
      .from({ entry: entries })
      .select(({ entry }) => ({ id: entry.id }))
      .execute();
  },
  { isolationLevel: "serializable" },
);
```

共同隔离级别为 `readCommitted`、`repeatableRead`、`serializable`。省略时保留连接原来的默认隔离行为。PostgreSQL 和 MySQL 另外支持 `readOnly: true/false`；HANA 的显式池适配器目前在借连接前拒绝这项选项。

HANA 数据库本身支持 READ ONLY，但访问模式会跨 COMMIT/ROLLBACK 保留。SAP Node 客户端 2.30.27 的显式连接池实测没有复原它，也没有可靠的公开单租约 discard API。当前适配器因此不改变共享池的访问模式，不通过清空应用整个池来处理异常。这是适配器能力边界；应用自行执行设置会话状态的原生 SQL 时，也必须自行管理恢复。隔离级别已通过容量为 1 的池验证复原，读取使用 `CURRENT_TRANSACTION_ISOLATION_LEVEL`。

PostgreSQL 将选项放入 BEGIN。MySQL 先使用无 SESSION/GLOBAL 的 SET TRANSACTION 设置下一事务，再 START TRANSACTION；任一控制语句失败都会销毁该租约，避免遗留待消费的设置。MySQL 提交和回滚显式使用 AND NO CHAIN NO RELEASE，不受应用池 completion_type 默认值影响。[PostgreSQL BEGIN](https://www.postgresql.org/docs/17/sql-begin.html)、[MySQL 事务选项](https://dev.mysql.com/doc/refman/8.4/en/set-transaction.html)、[MySQL 事务结束](https://dev.mysql.com/doc/refman/8.4/en/commit.html)

PostgreSQL 已失败的事务可能把 COMMIT 响应为 ROLLBACK，而不再抛异常。适配器检查 command tag，并将原业务 SQL 错误保存在 cause 中；回调捕获 SQL 错误后正常返回，也不能把已经回滚的事务报告为提交成功。[PostgreSQL 提交处理源码](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/backend/tcop/utility.c)

## 扩展适配器与生命周期

`DriverConnection.begin(options)` 接收经过验证和冻结的选项。自定义 `Driver` 使用 `transactionCapabilities` 声明支持的隔离级别和 readOnly；旧适配器仍可执行无选项事务，但没有声明的选项会提前拒绝，不能静默忽略。

事务回调只使用传入的 tx，并 await 其中所有操作。捕获外层 db 的查询仍会另借连接，不能自动加入当前事务。回调结束立即使 tx 失效；正在等待的分页 COUNT 或写入批次不会因此继续启动后续 SQL。这里不承诺取消已经发出的数据库请求。提交、回滚、归还的错误保留原始原因；多个失败用 AggregateError 表达。

连接池由应用创建和关闭，ORM 只管理借出的连接。嵌套事务／savepoint 仍未开放；不要在 ORM 管理的事务中手动执行 BEGIN/COMMIT/ROLLBACK 来改变同一租约的所有权。
