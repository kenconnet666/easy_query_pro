# 派生关系与投影读取映射

三库 Pro 扩展在既有查询执行链中保留派生表、普通 CTE 和 UNION 的列读取信息。整列 JSON 业务对象、PG 数组/业务 List、interval 等仍由原 handler/ValueConverter 读取；不新增序列化库或第二套结果执行器。

## 读取优先级

1. 本次投影显式指定的 `valueConvert` 保留优先级。
2. 普通 Bean/DTO 使用目标属性的 handler/转换器。源 Integer 到目标 Long 等普通投影不被自动源读取器覆盖。
3. Draft 和基本结果需要保留源列映射时，按具体来源关系和输出列名解析；不同 CTE 的同名 `valueN` 不共享读取契约。
4. JSON 转文本、数组元素/长度、聚合和其他产生新标量的表达式使用它们的结果类型，不继承整列转换器。

内部通过 `ExpressionBuilderFactory` 的关系创建时机复制已注册 reader。EasyQuery 3.2.17 会在内外层复用表达式上下文，而外层 `valueConvert.as` 早于 selector 执行，因此不能到外层 selector 才读取原 map 来猜测哪些值属于上一层。Draft 的 resultPropTypes 又是在关系创建之后赋值，所以读类型解析延后，但 reader 注册先固定。隐式 `SELECT *` 继续沿用内层关系的列信息。

`SQLExpressionInvokeFactory` 的 selector 只调整自动 reader 与已知 Pro 标量分类。显式转换优先级、DTO 目标契约和聚合分类分别处理；没有把未知函数标为可跨分片逐行计算。

## UNION 和查询复用

UNION 按输出位置检查读取兼容性。相同默认标量类型/handler 可以组合；同一个显式 reader 经过透明传播后也可以组合。不同业务映射或不同显式转换不能由第一分支替所有分支解释，需先投影为共同 SQL 标量，再在最终结果做转换。兼容检查不通过函数名称或 lambda 文本猜测等价。

上游查询对象有可变构建状态。需要从同一中间查询创建多个独立分支时，调用 `cloneQueryable()`；增强包保留这一契约。正常的单链连续 select 不要求先 clone 或执行来触发读取器初始化。

CTE 等查询的 `singleNotNull`、`toList` 和 `toSQL` 可能通过上游 compensateSelect 给原构建器补全选择列。若还要从该查询派生其他投影，应在终端调用前克隆，或提前构造各分支；终端调用之后才 clone 会连同补全列一起复制，后续 select 可能追加到已有列。测试与报表示例按这个公开可变构建契约使用查询。

## 安装与验证

Pro 扩展注册 `ExpressionBuilderFactory` 与 `SQLExpressionInvokeFactory` 两个公开服务。可继承默认实现收纳于 `dsl.spi`，通过 protected 创建钩子替换局部 selector/关系构造，CTE clone 继续经过原工厂。应用已有自定义工厂时，需要在同一工厂中组合相关行为；服务注册顺序决定最终实现，不能用第二次替换悄悄丢掉已有能力。扩展方式和本次包迁移见 [DSL 扩展说明](dsl-extensions.md)。

实库回归覆盖 MySQL 两种预处理和两种 SQL mode、PG JSON/JSONB、数组/List、interval，以及 HANA NCLOB 业务值与长 JSON 文本。另有显式 valueConvert、普通 DTO 类型转换、上游 SUM、无预先 clone 的连续投影、UNION 同源/异源 reader、重排和 clone 的回归。

这组读取验证不等于全部 SQL 结果类型限制已经闭合。HANA LOB 在多层派生查询中的 DISTINCT/排序约束、完整复杂报表和所有聚合组合仍按 [统计报表蓝图](reporting-blueprint.md) 与 G4.6/G5 继续验收。
## 单列结果读取器

整值 Pro 表达式将来源 ColumnReader 带入 `selectColumn`，包括 Draft/CTE 已暴露具体业务 record 类型的情况。有 reader 的表达式使用上游 Object 标量载体，Java 泛型 T 保持不变；局部 ResultColumnMetadata 适配只调用一次 reader，避免重复解密或业务转换，不替换全局 JDBC handler 或执行器。

映射值 FIRST/LAST/NTH_VALUE 与整值 MIN/MAX 窗口复用同一路径。空 frame 的 primitive 值先作可空 JDBC 读取，再调用原业务转换器；不透明的显式 reader 保留自己的 NULL 契约。普通 DTO 仍使用目标映射。
