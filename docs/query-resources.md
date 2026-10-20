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

2026-10-21 06:43 的资源观测样本使用 JBR 17.0.14、EasyQuery 3.2.17、Hikari 7.0.2。测试驱动为 MySQL Connector/J 9.7.0、pgJDBC 42.7.14、ngdbc 2.30.11。数据库版本由本地连接读取确认。

| 数据库 | 本地版本 | 业务 SQL / JDBC execute | Statement / ResultSet 门面 | 测量段耗时 |
| --- | --- | ---: | ---: | ---: |
| HANA | 2.00.088.00.1760424921 | 34 / 34 | 34 / 68 | 668 ms |
| MySQL | 8.4.11 | 34 / 34 | 34 / 34 | 1,231 ms |
| PostgreSQL | 17.11 | 34 / 34 | 34 / 34 | 157 ms |

这些是一次本地运行记录，包含断言、代理观测、Java 映射与短并发调度，排除了建表、填充数据、创建池和预热 count；不能据此比较数据库速度或承诺生产延迟。CI 的硬门槛是固定结果、SQL 次数、资源状态及有界任务完成，耗时只输出供后续同环境对照。

## 预热后的重复物化样本

`QueryBenchmarkContract` 在资源契约之后复用同一物理表与最多两连接的池，再创建普通/增强 client。JDBC、普通 ORM 和 Pro CAST 均完整物化同一个 `TestProduct` DTO，按 id 排序，逐行比较 id、名称、DECIMAL 和 NULL。每个入口预热 3 轮、测量 7 轮，每轮轮换首个入口；断言在计时段之外，每次读取结束都检查活动连接为零。三个入口共新增 31 个逻辑读取（1 个参考、9 个预热、21 个测量），不计入前面资源阶段的 34 条 SQL 预算。

这段测量不带 JdbcResourceProbe 代理。直接 JDBC 使用普通 ORM 预先生成且确认无参数的 SQL，并创建同类 DTO；普通 ORM 和 Pro 每次创建查询并生成 SQL。Pro 的 DECIMAL CAST 还会涉及数据库工作，因而这是几个真实使用入口的端到端对照，不能从差值单独拆出 DSL、mapper 或服务器的开销。HANA 的普通 ORM 仍使用本项目 HANA adapter，只是不安装 Pro DSL 扩展；上游自身没有 HANA 后端。

以下为 2026-10-21 07:38 同一环境的 7 个测量样本分布，单位 ms。原始微秒序列输出到标准 Failsafe 报告，未只保留最快值。

| 数据库 | 入口 | 最小 | 中位 | 最大 |
| --- | --- | ---: | ---: | ---: |
| HANA | JDBC | 7.591 | 10.017 | 11.918 |
| HANA | 普通 ORM | 7.950 | 9.497 | 10.837 |
| HANA | Pro CAST | 8.471 | 10.209 | 11.668 |
| MySQL | JDBC | 45.882 | 48.050 | 53.881 |
| MySQL | 普通 ORM | 47.134 | 48.325 | 53.004 |
| MySQL | Pro CAST | 48.069 | 50.492 | 53.734 |
| PostgreSQL | JDBC | 3.279 | 3.739 | 4.460 |
| PostgreSQL | 普通 ORM | 3.498 | 4.702 | 8.400 |
| PostgreSQL | Pro CAST | 3.956 | 4.313 | 5.886 |

各库内部的样本分布存在交叠，不据此认定某入口具有固定性能优势。这里也没有复制 JVM、采集 GC/网络 profile 或控制其他开发负载，无法把单个高值直接归因于某段代码。后续同环境、多轮次出现稳定退化时，应保留原始序列、输入、版本和资源配置再定位；不为共享 CI 设置绝对延迟门槛。这一小型重复基线不替代大 LOB、复杂报表及长期压力的成本验收。

先按 [开发环境](development.md) 设置对应数据库环境变量，再用 JDK 17 执行：

```powershell
.\mvnw.cmd --batch-mode verify '-Phana,mysql,postgres' `
  '-Dtest=HanaConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dit.test=HanaQueryResourceIT,MySqlQueryResourceIT,PostgresQueryResourceIT'
```

三个用例已进入原数据库 profile 和 CI，无需新增容器。测试按 worker → result/transaction → client workers → pool → 本次表/schema 的顺序结束；持久开发数据库、容器和共享缓存保持。受限用户的物理表报表与全文权限验证单独记录，不把此处使用 fixture owner 的资源测试称为非管理员验证。
