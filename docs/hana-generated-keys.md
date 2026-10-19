# HANA 生成键与执行生命周期

当前验证基线为 EasyQuery 3.2.17、HANA 2.00.088 和 ngdbc 2.30.11。调用方式仍是既有 `executeRows(true)`；不需要新的插入 DSL。普通无回填写入继续走上游执行器。

## 正确性边界

该驱动不支持标准 JDBC generated-key 重载。适配器逐行执行 INSERT，并在同一个 `EasyConnection` 上读取 identity 或已注册 sequence 的 session 值，复用上游 JDBC handler 和实体 setter。不能在 JDBC batch 完成后用最后一个值推算前面的键，也不能用 `MAX(id)`。

`HanaSequenceGenerator` 继续使用上游生成表达式注册机制。未知自定义 generator、多个列共用同一个 session key source 会在写入前明确拒绝。

HANA 的 `CURRENT_IDENTITY_VALUE()` 是 session 状态。真实触发器测试已经证明：目标表生成 1/2，但触发器向另一张 identity 表插入 100/101 时，直接回填会错误得到 100/101。不能将“同一个连接”误认为“当前 INSERT 的生成值”。

目前对存在启用 INSERT 触发器的目标表，自动回填在写入前以 SQLState `0A000` 拒绝。保护同时适用于 session sequence 回填，因为触发器也可能消耗同一 sequence。不请求回填的 `executeRows(false)` 仍可执行触发器；需要可靠主键时可以使用应用生成的键。该限制是原生生成值获取路径的边界，不能以静默回填或推算替代。

目标检查来自结构化表信息，不解析已渲染的 SQL。每次回填执行检查 `SYS.TRIGGERS`，不缓存“没有触发器”的结论；已验证同一 client 在创建、删除触发器前后的行为。普通非管理员跨 schema 的 SELECT/INSERT 授权也已通过测试，不要求额外授予 CATALOG READ。

需要 schema 时使用 `asSchema` 与 `asTable` 分开指定。预引用名称会先解码用于元数据匹配，再规范引用供 SQL 渲染。当前回填路径要求可见的实际表元数据，拒绝 view/synonym、raw table fragment、分片后尚未解析的物理目标以及点号拼接的复合标识符；无回填路径不受此保护器限制。这些场景若进一步扩展，必须继续携带可靠目标信息，不能靠 SQL 字符串猜测。检查 synonym 的别名而不解析真实基础表，会漏掉后者的触发器，故不能默认放行。

元数据检查不提供并发 DDL 协调；触发器、目标表或 synonym 的结构变更应放在受控部署阶段。未来若采用其他生成值获取机制，必须重新验证这些边界，而非仅移除保护。

## 回调时序

回填插入的时序为：

1. 触发既有 JDBC before 回调。
2. 在目标连接检查触发器和回填前提。
3. 通过上游参数提取与绑定设施设置当前实体的参数。
4. 执行 INSERT，立即读取并回填当前实体的键。
5. 完成同批各行并释放 statement/result set。
6. 触发既有 JDBC after 回调，再由上游继续实体事件和连接管理。

INSERT 与键读取之间没有用户回调。内部键 SELECT 不再发送业务 JDBC listener 事件，以免 listener 中的嵌套写入覆盖 session 状态；开启 SQL 日志时仍记录该 SELECT。after 回调看到的是已经回填的实体。

回填循环自己控制这一小段 JDBC 生命周期，因为上游 `DefaultJdbcSQLExecutor.insert` 返回前已经执行 after 回调，没有可插入键读取的安全钩子。实现仍复用上游参数提取/绑定、类型处理、日志、事务与执行分发，其他查询和写入不复制执行器。

失败后，after 参数包含已完成的行数、已尝试的参数和原始数据库异常。如果 after 自身再次失败，其异常作为 suppressed exception 保留，不覆盖数据库错误。

## 批量、事务与连接池

每批复用 INSERT 和键读取两个 prepared statement，仍按行执行。额外成本约为每行一次键 SELECT，加每批一次触发器元数据查询；不把这种路径宣称为原生 JDBC batch 性能。

`batch(true)` 本身不提供原子性。需要整批回滚时使用原有显式事务；回滚后数据库行消失，但 Java 对象已经取得的 ID 不自动清空，identity/sequence 也可能留下间隙。

HikariCP 7.0.2 仅以 test scope 引入，不成为生产模块依赖。当前短测试使用 4 个工作线程、最多 2 个物理连接、每线程 6 行，其中一个事务回滚；校验了 24 个键不重复、18 行提交值与实体逐一对应、复用连接恢复 auto-commit、无活动连接残留，以及关闭连接池。

复现测试为 `HanaIdentityIT`、`HanaSequenceIT`、`HanaKeyLifecycleIT`、`HanaConnectionPoolIT`。完整长时间压力与资源基准仍属于 G6 验收，不能用这组短测试替代。

参考实现和原生契约：[Hibernate HANA identity support](https://github.com/hibernate/hibernate-orm/blob/main/hibernate-core/src/main/java/org/hibernate/dialect/identity/internal/HANAIdentityColumnSupport.java)、[HANA SQL Reference](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)、[HikariCP 7.0.2](https://github.com/brettwooldridge/HikariCP/tree/HikariCP-7.0.2)。当前实现取舍以本项目可复现测试为依据，并未照搬其他 ORM 的 session 值假设。
