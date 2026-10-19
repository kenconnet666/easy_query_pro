# HANA 写入契约

当前基线为 EasyQuery 3.2.17、HANA Platform 2.00.088、JDK 17。普通插入、更新、删除继续使用已有 API；生成键和触发器边界见 [生成键契约](hana-generated-keys.md)。本文件记录已实现路径与仍未关闭的写入问题，不缩减完整蓝图。

## 冲突写入

`onConflictThen` 翻译为一条原生 MERGE：

```java
query.insertable(product)
        .onConflictThen(p -> p.FETCHER.amount())
        .executeRows();

query.insertable(product)
        .onConflictThen(null, p -> p.FETCHER.name())
        .executeRows();
```

第一种默认以实体主键匹配并更新选定列，第二种按显式唯一列匹配，已存在时不更新。冲突列可以是复合键，必须在本次插入列中提供值。执行前通过 `SYS.INDEX_COLUMNS` 校验目标列集合对应实际主键或唯一索引；单纯的普通索引或没有唯一约束的列不能作为冲突目标，避免一次匹配更新多行。

元数据检查不查询业务行。记录是否已存在仍由 MERGE 自身判断，不实现“先 SELECT，再 INSERT/UPDATE”的竞态流程，也不为约束变更提供并发 DDL 协调。

值表达式只在 MERGE source 中生成一次，后续匹配、UPDATE、INSERT 复用列别名。参数、SQL 属性转换器和普通批处理继续由上游处理；没有 SQL 字符串回写或第二套执行器。

更新不会改动主键和冲突键，也不会更新本次插入中未提供的列。需要把选定字段写成 NULL 时，沿用 `setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS)`。没有指定冲突目标时，HANA 使用主键；其他唯一约束冲突会正常报错，不会被“忽略主键冲突”吞掉。

## 行数与并发

- 插入一行返回 1；匹配并更新一行返回 1，即使写入值未变化；匹配但忽略返回 0。
- 批处理中重复输入同一个键，沿原有 JDBC 命令顺序处理，不在 JVM 静默去重。当前验证中后一次更新生效，受影响行数为各命令结果之和。
- 并发更新既有行与并发首次插入都已有短测试。首次插入竞争若产生 HANA 301 唯一键异常，会原样向调用者报告；不会转换为成功，也不隐式重试包含应用副作用的整个操作。
- 需要整批原子性时使用已有显式事务；已验证约束错误后的回滚和后续连接可用。

MERGE 的 session-generated-key 回填目前明确拒绝。匹配 UPDATE/忽略时，session identity 不代表本次结果；`executeRows(true)` 不能安全推断键。普通 `executeRows()` / `executeRows(false)` 和应用生成键可用。结构化目标解析沿用回填保护器，因此 raw table fragment、未解析的分片目标、点号拼接 schema/table 等仍需后续扩展，不默许不可靠的目标识别。

本地 `HanaConflictWriteIT` 的 8 个测试涵盖：原生参数化 MERGE、Unicode 与 DECIMAL(19,4)、重复渲染、主键/唯一索引/复合键、选择列、NULL、重复批量输入、非法目标、回填拒绝、失败回滚和并发。完整矩阵仍以相应提交的 CI 为准。

## 导航关联更新与删除

已有导航属性产生的多表写入现在由 HANA 翻译，仍使用上游的表、SET、ON 和 WHERE 节点：

- 关联 UPDATE 生成 MERGE。source 只计算一次主键和待写入值，支持复合主键、参数、SQL 属性转换及版本字段；只写根表。目标必须声明主键，raw target fragment 明确拒绝。
- 原生 UPDATE FROM 会对同一目标行执行多次更新，结果可能依赖匹配顺序。采用 MERGE 后，重复匹配产生原生 689 错误，测试确认整条语句原子失败；不选择某一行，也不先查数据再更新。
- 关联 DELETE 生成相关 EXISTS，以一行 SYS.DUMMY 代表当前外层目标行，再使用原有 join/predicate 节点。无需目标主键；LEFT JOIN 未匹配行不会丢失。内部别名避开用户别名。
- 不覆盖上游的关联规则：EasyQuery 3.2.17 普通导航在查询中默认 LEFT JOIN，在 UPDATE/DELETE 中默认 INNER JOIN；分组导航可以产生 LEFT JOIN。已有 Version 规则同样适用于相关实体，表达式写入应明确使用 `withVersion`，或有意调用 `ignoreVersion`。

物理删除仍需上游 `allowDeleteStatement(true)`，逻辑删除保持转换为更新。HANA 翻译不会移除租户过滤、逻辑删除或版本谓词，也不会在空 WHERE 时偷偷进行整表关联写入。

`HanaBusinessIT` 验证导航赋值、重复匹配失败、关联删除、无匹配子项的分组 LEFT JOIN、逻辑删除、复合键跨租户隔离、旧版本不覆盖、事务回滚、实体/租户拦截器、include 与多 client 隔离，以及无主键删除和别名冲突。完整多数据源/分片仍继续验收，不由这些用例代替。

原生 UPDATE FROM 的重复匹配语义见 [SAP HANA Platform 2.0 SQL Reference，UPDATE](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)。本地 hdbsql 已独立复现 MERGE 的 689 错误及零误写，以及 DELETE 别名/相关 LEFT JOIN 的实际行为。

## 事务、行锁与连接初始化

既有 `forUpdate()` 继续使用上游事务检查和 SQL 渲染。必须先开启事务；已验证单表 WHERE/ORDER BY/LIMIT 组合在另一事务上产生真实锁等待，并分别在 commit/rollback 后释放。测试通过 `M_BLOCKED_TRANSACTIONS` 确认等待，未把 future 暂未结束当作锁生效的证据。JOIN 的显式锁定目标、额外 WAIT/NOWAIT 等变体尚未作为已支持接口交付。

当前 HANA 支持的 JDBC 隔离级别如下：

| JDBC 请求 | 当前服务端监控值 | 已验证的可见性 |
| --- | --- | --- |
| READ_COMMITTED | READ COMMITTED | 同一事务的后续语句可以看到其他连接新提交的版本 |
| REPEATABLE_READ | SERIALIZABLE | 同一事务继续看到首次读取的快照 |
| SERIALIZABLE | SERIALIZABLE | 同一事务继续看到首次读取的快照 |

revision 88 的 `M_TRANSACTIONS` 将原生 REPEATABLE READ 也报告为 SERIALIZABLE；已通过独立 hdbsql 会话复核，并检查 ngdbc 2.30.11 的请求映射确实发送 REPEATABLE READ。适配器没有把 JDBC 4 改成 JDBC 8。测试还直接验证了跨连接的快照可见性，避免只根据监控字段下结论；这不替代所有并发异常类型的长期验证。

READ_UNCOMMITTED 和其他未支持数值在借用连接前明确拒绝。发现并修复了上游连接初始化的失败路径：设置不支持的隔离级别时，物理连接已经借出，却没有归还池。HANA 的 `EasyConnectionFactory` 适配保留上游数据源路由、等待策略、`DefaultEasyConnection` 和事务管理，只接管借用到初始化完成之间的资源责任。任何初始化失败会关闭本次已经借到的所有连接，包括尚未包装的连接；关闭失败挂到原始异常上。

`HanaTransactionIT` 验证 7 个行锁、隔离和失败恢复案例；`HanaConnectionFactoryTest` 用故障连接验证三连接初始化中途失败时全部归还，并保留 close 异常。单连接池复用后恢复 auto-commit 和原隔离级别，失败之后还可继续查询。连接池仍只是测试依赖；生产模块不创建自己的连接池。

## 零显式列插入仍未完成

上游 `InsertExpressionBuilder` 在没有插入列时就会抛错，早于数据库表达式渲染。这需要在正确的 builder 扩展层解决，不能只改最后的 SQL 字符串。普通省略列后的服务器默认值，与“所有显式列都为空”的插入不是同一个路径。

2026-10-20 在当前 revision 88 实测了 HANA 文档中的 identity override 路径：目标包含 `GENERATED ALWAYS AS IDENTITY` 和一个带默认值的普通列，仅指定 identity 列，使用 `OVERRIDING USER VALUE SELECT 0 FROM DUMMY`。这条语句触发 indexserver SIGSEGV，而不是普通 SQL 异常。首个崩溃记录的 UTC 时间为 `2026-10-19 17:37:04`，栈包含 `ptime::TBar::RemoteRequest_Encoder::decode` 与 `SQL::Update::PartUpdateStatisticsHandler::processPartUpdateStatistics`。

因此该翻译不会进入生产实现或默认 CI，也不应在共享开发实例重复执行。探测会话已终止、测试表已删除，租户自动恢复后的稳定状态已核对；相关 dump 仅提取了上述脱敏证据，没有把原始环境信息纳入仓库。该问题保持未关闭，后续需要独立实例验证可靠替代方案或已修复的 HANA revision，不能把当前拒绝当作完成。

原生语法依据：[SAP HANA Platform SQL Reference，INSERT 与 MERGE INTO](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)。上游 API 和选择列行为参照仓库的 3.2.17 实现及 [EasyQuery 冲突写入文档](https://github.com/xuejmnet/easy-query-doc/blob/main/src/en/ability/insertOrUpdate.md)。
