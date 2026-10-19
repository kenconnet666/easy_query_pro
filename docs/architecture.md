# EasyQuery Pro 分包与设计方案

状态：子项目与依赖边界已落地；具体功能 API 仍是设计方案。目标限定 MySQL、PostgreSQL、HANA，使用 Java 17，优先支持 HANA 2.0 本地部署。

## 设计原则

保留 EasyQuery 的查询链、实体代理、参数绑定、事务和结果映射。增强包负责补齐 HANA 后端，并以可选模块承载确有差异的类型和操作。通用操作继续走原有 API；同一语义能够跨数据库实现的新增能力优先提供统一入口。

首批以本地 EasyQuery 3.2.17 为精确依赖基线。增强模块使用 `maven.compiler.release=17`，不启用 preview。`record` 适合不可变的类型参数和配置值，`sealed` 只用于明确封闭的内部模型；面向用户扩展的方言与类型契约不封闭。实体映射先遵循上游现有方式，不因 Java 17 可用就默认支持 record 实体。

## 现有能力与实际缺口

以下根据本地源码核对。3.2.17 的更新包含 LIKE 转义模板等调整，后续实现应基于当前的 `LikeFunctionTemplateGenerator`，不恢复旧版重复逻辑。已复核数据库注册、APT 类型映射和窗口入口的分包结论仍适用。API 存在不等于所有数据库都实现了相同语义，后续仍须逐方言验证。

| 方向 | 上游已有能力与源码位置 | 增强包的处理 |
| --- | --- | --- |
| 数据库注册 | `sql-core/.../bootstrapper/DatabaseConfiguration.java`；PostgreSQL、Oracle 配置通过 `ServiceCollection` 注册实现 | HANA 复用这个入口，不创建第二套数据库注册框架 |
| SQL 方言 | `SQLKeyword`、`ExpressionFactory`、各数据库的 query/insert/update/delete expression | 补 HANA 实现；不假设换引号就完成适配 |
| 通用函数 | `SQLFunc` 及字符串、时间、数学、聚合、JSON 等子接口 | 优先实现既有语义，不增加另一套 concat/date/json API |
| JDBC 类型 | `JdbcTypeHandler`、`JdbcTypeHandlerManager`，以及 `@Column` 的 `typeHandler`、`jdbcType` | 复用读写与参数绑定扩展点 |
| 值与 SQL 转换 | `ValueConverter`、`ColumnValueSQLConverter`、`@Column.sqlExpression` | 分清 Java 值转换与 SQL 表达式转换，不重复创建 Converter 体系 |
| JSON | `SQLJsonFunc` 已有字段提取、key 检查、数组取值及长度；Proxy 层已有 JSON 表达式入口 | 先适配这些能力；JSONB 包含、路径查询等经差距审计后再补 |
| 窗口函数 | `SQLPartitionByFunc` 已有排名和窗口聚合；Proxy `extension/window/NextSelector` 已有 prev/next、firstValue/lastValue/nthValue | 不再实现 LAG/LEAD 和首末值 API；候选缺口是显式窗口 frame |
| 条件聚合 | Proxy `functions/type/filter` 已有 `.filter(...)`，官方文档示例通过 CASE 表达 | 不因没有直接输出 `FILTER (WHERE ...)` 就认定能力缺失 |
| CTE 与树查询 | `CTERecursiveProvider`、`QuerySQLExpressionImpl` 已有相关机制 | HANA 须区分普通 CTE 与递归查询；不能照搬 `WITH RECURSIVE` |
| 冲突写入 | `SQLConflictThenable`、`ProxyEntityConflictThenable` 已有 `onConflictThen` | 在 HANA 后端验证可对应的语义，不另建通用 upsert API |
| Code First | `DatabaseMigrationProvider`、`MigrationEntityParser`，Oracle 还有专用 `DatabaseCodeFirst` | 后续接入原接口；首批未支持的操作须明确拒绝 |
| SQL 扩展 | `SQLFunc.nativeSql/anySQLFunction`、`PropTypeColumn` 的函数与原生片段适配方法 | 内部复用表达式与参数机制，向使用者提供有类型的入口 |
| APT 类型映射 | `ProxyGenerateProcessor.TYPE_COLUMN_MAPPING`、`getPropertyColumn`、`PropertyColumn` 固定映射 Java 类型 | 自定义 JDBC 类型不会自动生成自定义 DSL 列；首批使用显式适配入口 |

源码路径前缀分别为 `com/easy/query/core`、`com/easy/query/api/proxy`、`com/easy/query/processor`。不能把源码中已完全注释的 `columns/proxy/AbstractSQLColumnProxy`、`SQLStringColumnProxy`、`EntitySQLFunc` 当作可用 SPI。

另一个集成边界：现有 Spring Boot starter 通过 `DatabaseEnum` 和 switch 选择数据库。外部 HANA 模块不等于天然支持配置 `database: hana`；首批使用原生 builder，后续核查 starter 的定制入口与服务注册顺序，再决定是否需要轻量集成模块。

## 当前分包

采用平铺 Maven 多模块。按用户本轮要求建立全部目标数据库模块的依赖边界；生产模块目前只含包声明，具体功能按后续阶段实现，不添加占位公共 API。

```text
easy_query_pro/
  pom.xml                     # 父 POM 与依赖管理
  easy-query-pro-hana/         # HANA 后端
  easy-query-pro-dsl/          # 通用 DSL 与必要桥接
  easy-query-pro-dsl-hana/     # HANA 专属操作与类型视图
  easy-query-pro-dsl-mysql/    # 复用上游 MySQL 后端
  easy-query-pro-dsl-pgsql/    # 复用上游 PG 后端
  integration-tests/          # 验证模块，不发布
  docs/
```

| 模块 | 生产依赖 | 职责与边界 |
| --- | --- | --- |
| `easy-query-pro-hana` | 上游 `sql-core` | `HanaDatabaseConfiguration`、SQL 渲染、通用函数、必要 JDBC handler；后续迁移实现。不依赖 Proxy DSL、Spring 或连接池 |
| `easy-query-pro-dsl` | 上游 `sql-api-proxy` | 真正缺失的跨库表达式能力、与上游表达式互操作的最小公共代码。不依赖具体数据库模块或 JDBC 驱动 |
| `easy-query-pro-dsl-hana` | `easy-query-pro-dsl`、`easy-query-pro-hana` | HANA 专属类型视图、函数与版本限制，显式导入后使用 |
| `easy-query-pro-dsl-mysql` | `easy-query-pro-dsl`、上游 `sql-mysql` | MySQL 专属类型与操作，不重复实现现有 JSON、窗口函数或适配器 |
| `easy-query-pro-dsl-pgsql` | `easy-query-pro-dsl`、上游 `sql-pgsql` | JSONB 等确有价值的数据库类型增强，不复制 PostgreSQL 适配器 |
| `integration-tests` | 按 profile 选择各模块和驱动 | 跨库契约、真实 JDBC、隔离消费工程与编译验证 |

HANA 适配模块内部按 `config`、`expression`、`function`、`type` 组织；到实现迁移时才创建 `migration`。方言 DSL 内按 `function`、`type` 等实际职责组织。Java 包统一为 `org.dpdns.zerodep.easyquery.pro` 下的 `hana`、`dsl`、`dsl.hana`、`dsl.mysql`、`dsl.pgsql`，不占用 `com.easy.query` 上游命名空间。

Maven groupId 保留 `org.dpdns.zerodep.easy_query_pro`，artifactId 采用上表的连字符名称，父 artifactId 为 `easy-query-pro-parent`。所有 EasyQuery 依赖通过单一版本属性管理，避免 core、proxy、processor 不同版本混用。

暂不创建 `core`、`spi`、`common`、BOM 或全库聚合包；跨模块同一变化点出现多次后再提取有实际收益的公共层。数据库驱动与测试框架不传递给生产模块使用者。

## 方言类型与 DSL

需要区分三个不同维度：

1. Java 业务值类型，例如 `String`、`BigDecimal` 或用户值对象。
2. 数据库存储与 JDBC 绑定类型，例如 NVARCHAR、NCLOB、JSONB，以及长度、精度、scale。
3. 表达式允许的操作，例如普通字符串函数、JSONB 包含、HANA 全文查询。

不能仅依据 `String.class` 在运行时替换所有字符串列，也不能为了数据库差异要求每个普通字段换成 `HanaString`。不同存储类型可以共用同一个 Java 值类型，专属 DSL 必须显式指定意图。

建议提供按当前查询上下文获取的 `HanaDsl`、`MySqlDsl`、`PgSqlDsl` 入口，以方法参数传入数据库类型描述或专用列视图。以下仅为 API 草图，不是已经存在或已经编译验证的 API：

```java
// Ordinary operations keep the upstream DSL.
entity.name().contains(keyword);

// Proposed explicit access to a vendor operation.
HanaDsl.of(entity).text(entity.name()).fuzzyContains(keyword, 0.8);
PgSqlDsl.of(entity).jsonb(entity.payload()).contains(document);
```

类型描述可以采用 `HanaTypes.nvarchar(length)` 一类工厂，但要区分“已知存储类型的视图”和“生成 SQL CAST”的操作，不能让一个 `as(...)` 隐式承担两种语义。返回类型尽量与上游 `PropTypeColumn`、字符串/数字/布尔表达式兼容，让结果继续参与 select、where、orderBy 和函数组合。

首批强类型目标是专属入口和参数约束：PG 类型不能传给 HANA 专属方法，参数和返回 Java 类型由泛型约束。上游查询链本身没有数据库泛型，因此不能宣称所有跨库混用都能编译期发现；必须在构造或渲染时核对当前 runtime、数据源方言、版本与列上下文。

若真实用例表明公共的 `DialectType<D,T>` 有收益，再从 HANA 与 PostgreSQL 的实际实现提取。初期不把 `<D>` 扩散到整个 Queryable、Join 和实体 Proxy 泛型链。也不复制整个 APT；如果最终确实需要实体属性直接生成专属列类型，应单独设计上游可扩展的类型映射 SPI，并作为公共契约变更再次确认。

表达式实现必须保留别名、嵌套函数、参数顺序和表访问元数据；使用现有 `PropTypeColumn.columnFuncSelector` 等桥接能力，不从渲染后的 SQL 中解析列名。值走绑定参数；只能作为 SQL 语法出现的枚举、长度和边界值应通过受控类型及校验生成。还要验证子查询、CTE、投影复制后的表达式，不只验证直接列引用。

## HANA 适配范围

产品目标是 HANA 2.0 本地部署。当前参考规范为 SPS 08；最终最低支持 SPS/revision 与 JDBC 驱动版本须由真实运行矩阵确定，不能把 SPS 08 文档直接当作所有 HANA 2.0 版本的保证。HANA Cloud 暂不列入首发兼容承诺。

第一批覆盖：schema 与双引号标识符、分页和稳定排序、查询/连接/子查询/分组、基础增删改、参数绑定、事务、主键回填、常用通用函数和基本类型往返。

重点审计：

- 上游默认函数可能含特定数据库语法，例如 `DateTimeFormatSQLFunction` 默认使用 `DATE_FORMAT`；每项应归类为直接复用、HANA 替换或明确不支持。
- 字符串索引起点、空字符串与 NULL、Unicode、decimal 精度、日期时差方向、时间精度和时区不可仅靠 SQL 文本相似判定兼容。
- 单条/批量插入、identity/sequence 与 generated keys 分别验证；不能用 `MAX(id)` 代替主键回填。
- UPDATE/DELETE 别名、多表更新、锁选项、分页与锁组合需要独立验证。
- SAP HANA 2.0 SPS 08 的 WITH 不支持递归查询。首批应识别并拒绝不支持的树形递归；后续评估通过 HIERARCHY 实现现有树查询的可等价子集。不能删掉 RECURSIVE 关键字后继续执行，也不能悄悄全量查回内存计算。
- `onConflictThen` 复用上游入口，后续逐项验证 HANA MERGE/UPSERT 与冲突目标、只更新指定字段、受影响行数、并发语义的对应关系。
- Code First 未完成时不能落到其他数据库的默认迁移 SQL；显式拒绝未支持操作。后续默认围绕应用自己的表、schema、主键、索引实现。

## 功能增强优先级

| 优先级 | 候选方向 | 进入实现的条件 |
| --- | --- | --- |
| P0 | HANA 上的现有通用能力 | 形成完整支持矩阵与真实数据库回归 |
| P1 | 显式窗口 frame：先 ROWS，后 RANGE | 当前已读 `OverExpression` 只有 partition/order；再次确认无等价入口，保持现有 offset/aggregate API，跨 HANA/PG 对比结果 |
| P1 | 数据库类型描述与专属表达式桥接 | 用一个 HANA 专属操作和 PostgreSQL JSONB 用例证明设计；必须通过类型负例与运行时错库校验 |
| P2 | JSONB 包含等增量操作 | 普通 JSON 提取继续复用上游；区别 JSON null、SQL NULL、缺失键与数组语义 |
| P2 | HANA 全文检索与评分 | 专属入口，验证索引前提、模糊阈值、返回结果与评分关联 |
| P2 | HANA 层级查询 | 先核对现有树查询契约；能等价的部分优先沿用通用入口 |
| 待需求 | ordered-set 聚合、分位数、分组集合 | 当前搜索未发现专用 API；须再次做完整调用链审计并提供实际业务用例 |

暂不将统一 RETURNING、多数据库 MERGE 框架、时序/空间/向量全集列为首发目标。这些方向涉及结果执行协议、复杂类型或大型 SQL 结构变化，应单独评估。

## 参考与取舍

根据实际问题选择 Java、C# 或 TypeScript ORM 的成熟实现作参考；必要时可以克隆源码并固定版本。先对照 EasyQuery 现有能力，再判断语义、表达式模型与执行方式是否适配。只保留能解决具体缺口的设计，不将外部 ORM 的整套抽象作为默认目标。当前工程初始化不需要新增参考仓库。

- [SAP HANA 2.0 SPS 08 SQL Reference](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)：WITH 的递归限制见印刷页 1132，LIMIT/OFFSET 见印刷页 1149。作为指定版本语法依据。
- [Hibernate ORM 7.1 HANADialect](https://github.com/hibernate/hibernate-orm/blob/7.1/hibernate-core/src/main/java/org/hibernate/dialect/HANADialect.java)：参考分页、标识符大小写、类型与 JDBC 特例的拆分方式；不引入 Hibernate 作为运行依赖。
- [jOOQ 3.21 数据类型](https://www.jooq.org/doc/3.21/manual/sql-building/data-types/)及 [Binding](https://www.jooq.org/doc/3.21/manual/sql-building/data-types/converted-data-types/data-type-binding/)：借鉴 SQL 类型、Java 转换、JDBC 绑定分工；在 EasyQuery 已有扩展点上实现，不搬入另一套类型系统。
- [EasyQuery offset 文档](https://github.com/xuejmnet/easy-query-doc/blob/main/src/func/offset.md)及 [条件聚合示例](https://github.com/xuejmnet/easy-query-doc/blob/main/src/ability/select/case-when.md)：与本地源码交叉核对，避免重复实现。
