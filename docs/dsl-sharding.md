# DSL 投影与分片边界

当前验证的是同一 DataSource 的两张真实分表，使用 EasyQuery 3.2.17 的 `ShardingEntityExpressionExecutor`、initializer、route 和原有合并器。MySQL、PostgreSQL、HANA 都执行同一消费契约。更完整的多 DataSource、业务类型、派生关系、UNION 和分组组合仍在 G4.6 验收中。

原生视图的追加实测包含 MySQL JSON 修改/包含判断和 Duration 完整投影/加减/微秒/日期时间组合，PostgreSQL JSON/JSONB 转换/修改、数组 ANY/包含/元素/长度以及 interval 日历组合，HANA NVARCHAR/NCLOB JSON 读取与 LOB 完整投影、标量文本/切片/二进制切片。它们传播现有逐行分类，并同时检查双输入操作的两个操作数；数组比较中的聚合元素仍会拒绝。

HANA 作用域包装通过公开 TableSQLSegment/visitor 保留被包装结果的类型，因此 LOB 的直接 DISTINCT 继续在执行前失败，显式转换为标量文本则可用。类型检测停在当前表达式的结果，不把其内部 LOB 操作数误认为最终结果。返回列保留映射 handler/ValueConverter，没有另外复制一套类型读取器。

## 逐行计算与聚合后计算

增强表达式在内部保留“只依赖当前行和参数”的分类，普通列、typed value、比较绑定值属于已知逐行输入。共同 CAST、JSON path、日期截断、NULLIF、NULL-safe 比较只有在全部操作数都满足这个条件时，才保留这一分类。它不通过 `AggregationType.UNKNOWN` 猜测：上游的算术包装可能把内部的 SUM/AVG 隐藏成 UNKNOWN。

已验证的逐行组合可以先在分片内执行，再由上游合并结果。例如嵌套 CAST、读取 JSON 字段、普通列的 NULLIF、NULL-safe 比较以及其 Draft 投影均可用；排序、count、分页和 clone 已在三库执行。已知逐行 CAST 之后使用原有 Draft SUM，也仍由上游按 SUM 合并。

反方向的 `CAST(SUM(amount) AS DECIMAL(...))` 不能用“每个分片先 CAST 再相加”代替，所以明确拒绝。未知函数的内部结构没有公开语义元数据，也继续拒绝作为 Pro 分片操作数；例如 `nativeCast(amount.sumBigDecimal().add(1), type)`。同理，当前不能证明 `nativeCast(amount.add(1), type)` 的内部没有聚合，因此也不放开。这个边界比拒绝所有派生表达式更准确，但尚不等于任意上游函数/方言视图都已支持分片。

## Draft 投影分类

实库复现了上游 `columnFunc` 将 UNKNOWN 普通函数标为聚合列的问题：同一个 CAST 的单列结果可读取，放进 Draft 后分片合并器报 UNKNOWN。增强包通过公开 `SQLExpressionInvokeFactory` / `SQLExpressionProvider` / selector 扩展点修正**已知 Pro 逐行标量**的投影分类，SQL、参数、别名解析、列 metadata、visitor 和克隆继续交给已有实现。没有替换分片执行器、查询语法树或聚合算法。

实际聚合及未分类的上游函数保持原投影路径。应用已有自定义 `SQLExpressionInvokeFactory` 时，需要在同一个工厂中组合这一投影能力；服务注册顺序仍决定最终工厂，不能用第二次替换悄悄丢掉前一个工厂的行为。

三库契约验证了 Draft、基本数值结果、自定义列名的 DTO 和 Map 别名；普通属性的 NULLIF 保留原列读取器。非分片 JSON 业务值、HANA 转换器和原生数值回归同时执行，防止修正分片分类时丢失既有映射。

## 验证范围

- `ShardingExpressionScopeTest`：三库离线检查已知逐行组合、聚合、隐藏聚合与未知包装边界。
- `ShardingDslContract`：三库双分表插入、路由、列投影、Draft、顺序、count、分页、JSON 过滤、NULLIF/NULL-safe、clone、已有 SUM 合并和不安全转换拒绝。
- `MySqlShardingDslIT`、`PostgresShardingDslIT`、`HanaShardingDslIT`：分别创建自己的测试表/schema；HANA 另外在自己的 schema 部署 JSON 文档函数。

测试结束删除自身表/schema，并关闭自己创建的分片 executor。每个 DataSource 的分片并发上限为 2，开发数据库与用户数据保持保留。更广范围的类型、连接池和跨数据源组合不能用这些测试结果替代。

分片排序继续遵守上游“排序列必须在投影中”的要求；测试显式选择 id，不暗中增加或改变业务返回字段。任意未分类的上游函数、计算字段排序和跨分片聚合后转换仍不能从本次结果推断为可用。
