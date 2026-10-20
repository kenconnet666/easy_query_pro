# DSL 与工厂扩展

通用入口和三库入口均允许继承。MySqlDsl、PgSqlDsl、HanaDsl 继承 ProDsl，使用同一个查询上下文，也直接拥有共同 CAST、NULLIF、日期和 JSON 读取能力。原有 `of(entity)` 入口继续可用。

## 挂载自己的 DSL 与表达式

`ProDsl.of(entity, factory)` 的返回类型为具体 D，工厂接收公开的 `dsl.spi.ExpressionScope`。自定义 DSL 可继承对应数据库 facade，通过受保护构造接入查询；数据库身份检查仍由该 facade 完成。

`view(source, factory)`、`nativeCast(source, target, factory)` 和 `value(value, target, factory)` 在挂载方法上约束 `E extends PropTypeColumn<T>`，结果保持 E 的具体类型。ExpressionFactory 自身仅约束结果为列表达式，避免 Java 17 因重复的依赖上界而无法推导普通 lambda；错误 Java 值类型仍在挂载时编译失败。工厂接收 scope、mappingSource 和已经构造好的 SQLFunction 回调。view 保留原映射来源；CAST/绑定值提供新的结果来源，不把输入业务转换器继续套在新类型上。

工厂必须消费这些输入，不能返回另一查询缓存的表达式。转换、参数校验和作用域仍由共同入口处理。自定义 Java 载体需要注册原有 JDBC handler，必要时配合 scalar EntityMetadataManager；一个类型描述符不会自动创造 JDBC 读取能力。

没有返回自身的 facade 不强加 SELF 泛型。保持同族的 JSON/interval/duration 操作使用协变返回与 recreate 钩子逐步开放；改变类型的 `asText`、元素访问和日期运算继续返回相应标量族。当前通用挂载已具备，专属同族基类按 G4.7 继续实现，不把仅删除 final 当作链式扩展完成。

## 局部替换默认工厂

`ProProjectionFactory` 提供 protected `createSelector` 和 `createAsSelector`；默认 Provider 会调用这些钩子。子类可以扩展 ScalarSelector/ScalarAsSelector，保留默认 reader 优先级和标量分类。自定义 ScalarColumn 通过 recreate 控制克隆后的具体实现。

`ProExpressionBuilderFactory` 提供 protected `createRelation`、`createCte`。CTE clone 返回原工厂重新创建的类型，同时传递原 reader 快照；不会重新读取已被外层投影修改的 map。Relation 的复制仍通过上游 runtime factory，并保留已有读取快照。

HanaSQLFunc、HanaExpressionFactory、HanaSQLSegmentFactory 允许继承，继续用 EasyQuery ServiceCollection 注册。只覆盖需要定制的函数/节点，并把默认处理委托给 super，以保留 HANA 的其余语义和保护。

## 当前开发版本的包迁移

ExpressionScope、MappedValueExpression、AbstractJsonExpression、ProProjectionFactory、ProExpressionBuilderFactory 从 `dsl.internal` 迁入 `dsl.spi`。当前为未发布的 1.0-SNAPSHOT，未保留旧内部路径的兼容桥接。直接引用旧路径的代码需要更新 import 并重新编译；所有 Pro 模块必须使用同一版本，不能把旧方言 JAR 与新共同 DSL 混用。

`ColumnReaders` 保持包内实现，不是应用扩展接口；读取声明通过既有 ColumnReader 和上述 factory 契约组合。JsonInput/JsonPath 等值对象及纯校验工具保持既有值语义，不需要为了工厂扩展而变为可继承状态容器。

`DslExtensionTest` 验证具体 facade/表达式返回类型、共同能力继承、错库/跨 client/参数校验、局部 selector 替换和 CTE 克隆。`DslCompilationTest.extensionFactoriesRetainTheirDeclaredValueTypes` 验证泛型正负例，禁止把数字工厂挂到 String 结果上。
