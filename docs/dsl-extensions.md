# DSL 与工厂扩展

通用入口和三库入口均允许继承。MySqlDsl、PgSqlDsl、HanaDsl 继承 ProDsl，使用同一个查询上下文，也直接拥有共同 CAST、NULLIF、日期和 JSON 读取能力。原有 `of(entity)` 入口继续可用。

## 挂载自己的 DSL 与表达式

`ProDsl.of(entity, factory)` 的返回类型为具体 D，工厂接收公开的 `dsl.spi.ExpressionScope`。自定义 DSL 可继承对应数据库 facade，通过受保护构造接入查询；数据库身份检查仍由该 facade 完成。

`view(source, factory)`、`nativeCast(source, target, factory)` 和 `value(value, target, factory)` 在挂载方法上约束 `E extends PropTypeColumn<T>`，结果保持 E 的具体类型。ExpressionFactory 自身仅约束结果为列表达式，避免 javac（JDK 8 与 17 均已验证）因重复的依赖上界而无法推导普通 lambda；错误 Java 值类型仍在挂载时编译失败。工厂接收 scope、mappingSource 和已经构造好的 SQLFunction 回调。view 保留原映射来源；CAST/绑定值提供新的结果来源，不把输入业务转换器继续套在新类型上。

工厂必须消费这些输入，不能返回另一查询缓存的表达式。转换、参数校验和作用域仍由共同入口处理。自定义 Java 载体需要注册原有 JDBC handler，必要时配合 scalar EntityMetadataManager；一个类型描述符不会自动创造 JDBC 读取能力。

没有返回自身的 facade 不强加 SELF 泛型。MySQL JSON 已提供 `AbstractMySqlJsonExpression<T,S>`，PG interval 已提供 `AbstractPgIntervalExpression<T,S>`；set/remove 与 plus/minus/negated 返回 S。应用只实现一个 recreate 钩子，连续操作后仍可调用自己的方法，原有公共接口的泛型数量保持不变。

recreate 的 mappingSource 是上一层表达式，必须与 SQL 回调一起原样传给新实例；回到最初实体列会丢失已组合的操作或 reader。公开基类构造保留错库、跨 client 与已知物理声明检查。改变类型的 `asText`、元素访问和日期运算继续返回相应标量族，不能继续调用 JSON/interval 专属方法。PG JSONB、MySQL duration 等剩余同族基类按 G4.7 继续开放。

## 局部替换默认工厂

`ProProjectionFactory` 提供 protected `createSelector` 和 `createAsSelector`；默认 Provider 会调用这些钩子。子类可以扩展 ScalarSelector/ScalarAsSelector，保留默认 reader 优先级和标量分类。自定义 ScalarColumn 通过 recreate 控制克隆后的具体实现。

`ProExpressionBuilderFactory` 提供 protected `createRelation`、`createCte`。CTE clone 返回原工厂重新创建的类型，同时传递原 reader 快照；不会重新读取已被外层投影修改的 map。Relation 的复制仍通过上游 runtime factory，并保留已有读取快照。

HanaSQLFunc、HanaExpressionFactory、HanaSQLSegmentFactory 允许继承，继续用 EasyQuery ServiceCollection 注册。只覆盖需要定制的函数/节点，并把默认处理委托给 super，以保留 HANA 的其余语义和保护。

窗口增强还安装 `ProSQLSegmentFactory` 与 `ProSmartPredicateProvider`：前者保留别名、子查询计算层和 DISTINCT count 的合法性，后者仅阻止窗口层的错误筛选下推。HANA 使用 `HanaProSQLSegmentFactory` 委托原生 LOB 行为。需要自定义这些服务时，在对应 Extensions.configure 之后注册子类，并委托默认保护；替换为上游裸工厂会同时移除这些保证。

`WindowSpec<S>` 通过 protected `recreate(State)` 保持具体规格类型，`window(ReportWindow::new)` 可挂载消费者方法。边界与示例见 [窗口说明](dsl-windows.md)。新增规格优先复用共同 partition/order/frame，不另建查询执行对象。

## 当前开发版本的包迁移

ExpressionScope、MappedValueExpression、AbstractJsonExpression、ProProjectionFactory、ProExpressionBuilderFactory 从 `dsl.internal` 迁入 `dsl.spi`。当前为未发布的 1.0-SNAPSHOT，未保留旧内部路径的兼容桥接。直接引用旧路径的代码需要更新 import 并重新编译；所有 Pro 模块必须使用同一版本，不能把旧方言 JAR 与新共同 DSL 混用。

`ColumnReaders` 保持包内实现，不是应用扩展接口；读取声明通过既有 ColumnReader 和上述 factory 契约组合。JsonInput/JsonPath 等值对象及纯校验工具保持既有值语义，不需要为了工厂扩展而变为可继承状态容器。

`DslExtensionTest` 验证具体 facade/表达式返回类型、共同能力继承、错库/跨 client/参数校验、局部 selector 替换和 CTE 克隆。`DslCompilationTest.extensionFactoriesRetainTheirDeclaredValueTypes` 验证泛型正负例，禁止把数字工厂挂到 String 结果上。

测试消费者 ReportJson/ReportInterval 复用上述基类，仅实现 recreate 和一个业务方法。`customWholeValueChainsKeepSelfButScalarResultsDoNot` 验证连续链的具体类型与标量负例；MySQL/PG 实库把自定义链放入 Draft、CTE 和列重排，检查业务对象、interval、布尔和时间结果。
