# 查询资源、读取失败与短并发验收

`QueryResourceContract` 用三库共同的固定数据验证结果、句柄生命周期和额外 SQL 数量。它继续走原 EasyQuery client、事务、流式结果与连接池；`JdbcResourceProbe` 只在测试中观测公开 JDBC 返回的句柄和执行调用，没有增加生产执行器。

## 固定场景

每库建立自己拥有的随机表或 schema，插入 2,049 行。id 从 1 开始，完整结果的 id 校验和为 2,100,225；每第 7 行名称为 NULL，每第 11 行金额为 NULL，其余金额为精确 DECIMAL。fixture owner 使用独立连接，不占两连接的业务池。

先以一次 count 预热，再验证以下场景：

1. 原实体查询完整读取，fetchSize 为 64；校验行数、校验和、名称与金额。
2. Pro DECIMAL CAST → Draft → CTE 完整读取，仍保持相同结果与 NULL。
3. 同一投影链消费 65 行后退出，关闭流，再回滚事务。
4. 查询级 reader 在第 129 次调用抛出固定异常；保留异常身份，关闭流并回滚，下一次查询仍可正常读取。
5. 四个 worker 共用一个已初始化 client 和最多两条连接，但各轮重新创建 Proxy、Queryable、reader 与 CTE。共 24 轮在普通 DTO、Draft、CTE 之间切换，所有名称只带本轮标签；另一次预期 reader 失败后，该 worker 与其他 worker 继续成功。

流式场景在 `JdbcStreamResult.close()` 之后、事务回滚及连接归还之前，断言活动连接仍为 1，且所有已取得的 Statement/ResultSet 已关闭。因此 Hikari 归还时的兜底清理不能掩盖流自身漏关句柄。之后继续验证活动连接归零、重新借出的连接 autoCommit 为 true、readOnly 为 false、表行数仍为 2,049。并发场景验证返回后的资源状态和 reader 隔离，不将其替代上述仍占有事务连接的流关闭证据。

## 驱动配置与观测边界

- PostgreSQL 使用显式事务、正 fetchSize 和 forward-only 结果，遵守 [pgJDBC cursor 条件](https://jdbc.postgresql.org/documentation/query/#getting-results-based-on-a-cursor)。
- MySQL 的独立测试数据源启用 useCursorFetch、useServerPrepStmts，并关闭不支持预编译时的静默降级。正 fetchSize 的方式见 [Connector/J 说明](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-implementation-notes.html)。
- HANA 使用当前 ngdbc 与原事务/结果包装；没有引入额外查询服务。

测试直接记录 ORM listener 与 JDBC execute 调用，预热后每库恰为 34 次：四次流式查询、五次关闭后的 count 检查、24 次并发成功查询和一次预期失败查询。驱动内部的 cursor fetch 不是新的业务 SQL，不能把执行次数说成网络往返次数；这里没有做网络抓包，也不据此宣称峰值内存为 O(fetchSize)。

ResultSet 数量表示观察到的 JDBC 门面。HANA 的时间读取基础会在 executeQuery 后通过 statement.getResultSet 取得读取入口，Hikari 7.0.2 的这两个调用会分别包装同一底层结果，因此本轮观察到 68 个门面，而业务 SQL/JDBC 执行都只有 34 次。所有门面均检查关闭；不能把门面数直接解释为 SQL 数或泄漏。

## 本地可复现样本

2026-10-21，JBR 17.0.14、EasyQuery 3.2.17、Hikari 7.0.2。测试驱动为 MySQL Connector/J 9.7.0、pgJDBC 42.7.14、ngdbc 2.30.11。数据库版本由本地连接读取确认。

| 数据库 | 本地版本 | 业务 SQL / JDBC execute | Statement / ResultSet 门面 | 测量段耗时 |
| --- | --- | ---: | ---: | ---: |
| HANA | 2.00.088.00.1760424921 | 34 / 34 | 34 / 68 | 668 ms |
| MySQL | 8.4.11 | 34 / 34 | 34 / 34 | 1,231 ms |
| PostgreSQL | 17.11 | 34 / 34 | 34 / 34 | 157 ms |

这些是一次本地运行记录，包含断言、代理观测、Java 映射与短并发调度，排除了建表、填充数据、创建池和预热 count；不能据此比较数据库速度或承诺生产延迟。CI 的硬门槛是固定结果、SQL 次数、资源状态及有界任务完成，耗时只输出供后续同环境对照。

先按 [开发环境](development.md) 设置对应数据库环境变量，再用 JDK 17 执行：

```powershell
.\mvnw.cmd --batch-mode verify '-Phana,mysql,postgres' `
  '-Dtest=HanaConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dit.test=HanaQueryResourceIT,MySqlQueryResourceIT,PostgresQueryResourceIT'
```

三个用例已进入原数据库 profile 和 CI，无需新增容器。测试按 worker → result/transaction → client workers → pool → 本次表/schema 的顺序结束；持久开发数据库、容器和共享缓存保持。受限用户的物理表报表与全文权限验证单独记录，不把此处使用 fixture owner 的资源测试称为非管理员验证。
