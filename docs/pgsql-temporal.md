# PostgreSQL 无时区时间映射

`PgSqlExtensions::configure` 为 `LocalDateTime` 与 PostgreSQL `timestamp without time zone` 安装 JDBC 4.2 映射。它保留年月日、时分秒和数据库支持的小数秒字段，不先转换成依赖 JVM 默认时区的 `java.sql.Timestamp`。

这个区别在夏令时缺口中有实际影响。JVM 时区为 `America/New_York` 时，`2024-03-10T02:30:00.123456` 是合法的 LocalDateTime，也可以存入无时区 timestamp。EasyQuery 3.2.17 原处理器经 `Timestamp.valueOf`/`getTimestamp` 会将绑定和读取都变为 `03:30`；本包以 `setObject(LocalDateTime)` 与 `getObject(..., LocalDateTime.class)` 保留 `02:30`。实现依据为 [pgJDBC 的 Java 8 时间映射](https://jdbc.postgresql.org/documentation/query/#using-java-8-date-and-time-classes)。

## 安装与定制

仍使用上游 `PgSQLDatabaseConfiguration`，在构建 client 前调用 `PgSqlExtensions::configure`。增强注册：

- `PgJdbcTypeHandlerManager`：保留上游 PostgreSQL UUID handler，并修正默认 LocalDateTime 映射。
- `PgJdbcSQLExecutor`：继续执行上游 query，在结果进入分片合并之前保留无时区时间字段。

原实体中的 `@Column(typeHandler = LocalDateTimeTypeHandler.class)` 仍可解析；没有应用注册的精确同类实例时，映射到修正后的默认处理器。若应用显式注册了 LocalDateTimeTypeHandler 新实例，精确类名查找优先使用该实例，不被兼容别名挡住。其他自定义处理器及其子类保留各自类名与实例。

应用可在构建后、首次实体元数据加载之前用 `appendHandler(type, handler, true)` 注册自己的处理器；后续 `PgSqlExtensions.configureTypeHandlers` 不覆盖这些定制。需要替换整个服务时，在 Pro 安装之后注册，或者继承上述两个公开类保留时间基础。元数据会缓存列处理器，不能在首次查询后才注册并假设已经加载的实体会自动更新。

数组和 interval 的显式 handler 配置步骤保持不变，见 [PG interval](pgsql-interval.md) 与 [PG 数组](pgsql-arrays.md)。安装不会打开数据库连接，也不改变 JVM 或数据库会话时区。

基于这套映射的固定微秒时间桶及三库缺月报表见 [时间分桶与缺月报表](reporting-time-series.md)。固定桶不会把 LocalDateTime 改成带时区时间点。

## 分片与读取边界

只替换 typed getter 不足以修复分片结果。上游排序/分组/去重可能先用无类型 getObject 缓存一行；pgJDBC 默认会返回已经经过 JVM 时区解释的 Timestamp，之后再转换为 LocalDateTime 无法恢复丢失的字段。

因此仅在分片结果进入 merger 之前，将真正的无时区 timestamp 保存为 LocalDateTime。普通查询继续保留 pgJDBC 无类型 getObject 返回 Timestamp 的契约，避免破坏读取该值的自定义 handler；默认 LocalDateTime handler 直接调用 typed getter 即可保留字段。

包装保留 JDBC 分片标识，直接使用 StreamResultSet 已有的 typed getObject，不需要捕获 Statement 或复制 ExecutorContext。参数、Statement 配置、监听 trace、游标移动与关闭继续属于原执行链；包装失败时会关闭已经取得的结果，并保留原异常及传播到包装层的清理异常。清理抛出 RuntimeException/Error 也不会覆盖原始失败，同一异常实例不会被重复抑制；上游结果集自行记录的 checked 关闭异常仍遵循其既有行为。

分片内部的无时区时间规范值是 LocalDateTime，自定义分片处理器可用 typed getObject 获取它；这与普通查询中直接读取 pgJDBC 无类型 Timestamp 的契约不同。

识别同时检查 JDBC 类型和 PostgreSQL 类型名。`timestamptz` 也可能报告 `Types.TIMESTAMP`，不能据此当作无时区时间处理。DATE、TIME、timestamptz 和显式 java.sql 类型继续走原有路径；本项不新增 Instant/OffsetDateTime 支持，也不把无时区字段解释成 UTC 时间点。显式 java.sql.Timestamp 的 JVM 时区行为没有被改成新的业务语义。

## 验证证据

PostgreSQL 17.11、pgJDBC 42.7.14 与指定 JBR 17 下，`prepareThreshold=0/-1` 两种模式验证：

- 数据库端 `to_char` 证明绑定未改写 DST 缺口字段；独立 server literal 验证读取。
- 夏令时重叠时段与 SQL NULL 保持正确结果。
- 显式旧处理器声明、自定义处理器及其类名查找继续工作，UUID 回读正确。
- timestamptz 的显式 Timestamp 投影仍保持原时间点。
- 两个真实分表上的排序、分组和去重保持 LocalDateTime；分组缓存路径未再次变为 Timestamp。
- 既有 interval 运算、单/双 DataSource 分片 DSL 回归通过。

测试临时改变 JVM 时区并在 finally 恢复；创建的 schema 在测试结束时删除。具体命令、红绿复现与当前提交 CI 分开记录在 [执行记录](execution.md)。
