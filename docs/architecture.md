# EasyQuery Pro 架构边界

完整功能、取舍和成熟度要求以 [完整交付蓝图](blueprint.md) 为准。本文件说明代码归属、依赖方向和实现约束，不单独缩小功能范围。

通用 API、数据库类型 DSL、查询级方言与底层适配的五层划分，以及三库完整类型/操作清单，见 [DSL 设计](dsl-design.md)。

## 当前工程

```text
easy_query_pro/
  pom.xml
  easy-query-pro-hana/
  easy-query-pro-dsl/
  easy-query-pro-dsl-hana/
  easy-query-pro-dsl-mysql/
  easy-query-pro-dsl-pgsql/
  integration-tests/
  infra/
  tools/
  docs/
```

生产模块当前只有包声明，以下是完整实现的职责分配，不代表功能已完成。

| 模块 | 允许的主要生产依赖 | 完整职责 |
| --- | --- | --- |
| `easy-query-pro-hana` | 上游 `sql-core` | HANA 配置、SQL 渲染、通用函数、JDBC 类型、元数据与迁移，蓝图 H01–H10 的主要实现 |
| `easy-query-pro-dsl` | 上游 `sql-api-proxy` | 精确类型/CAST、ROWS、JSON path 与组合、NULL-safe/NULLIF/日期截断、互操作和小型翻译接口 |
| `easy-query-pro-dsl-hana` | 通用 DSL、HANA 模块 | 通用新增的 HANA 翻译，以及 HANA 类型、全文、层级、参数化视图/表函数 |
| `easy-query-pro-dsl-mysql` | 通用 DSL、上游 `sql-mysql` | 通用新增的 MySQL 翻译，以及原生类型/JSON/时长、全文与 RANGE，复用后端 |
| `easy-query-pro-dsl-pgsql` | 通用 DSL、上游 `sql-pgsql` | 通用新增的 PG 翻译，以及 JSONB/数组/interval/检索类型、全文与 RANGE，复用后端 |
| `integration-tests` | 各模块和相应驱动，均按测试用途引用 | 编译、SQL、真实数据库、组合、并发、迁移和产物消费验证，不发布 |

调用方向保持为：业务调用 → 原有 EasyQuery 或显式增强 DSL → 上游表达式/运行时协议 → 当前数据库翻译与绑定 → DataSource/JDBC。增强模块不得建立平行执行器或反向要求上游依赖增强包。

HANA 底层模块保持与 Proxy DSL 解耦。专属 DSL 可以组合已有函数、查询和类型扩展；底层 SQL renderer 不反向依赖业务层 facade。

原有函数的 HANA 翻译属于底层模块；本项目新增通用函数的 HANA 翻译属于 `dsl-hana`。共同核心不导入三库实现，由显式安装的增强服务完成翻译，不替换整套上游 SQLFunc，也不复制执行器。

## 命名与目录

- Maven groupId 保持 `org.dpdns.zerodep.easy_query_pro`，父 artifactId 为 `easy-query-pro-parent`。
- Java 包使用 `org.dpdns.zerodep.easyquery.pro`，按 `hana`、`dsl`、`dsl.hana`、`dsl.mysql`、`dsl.pgsql` 分区。
- 实现目录在有代码时按 `config`、`expression`、`function`、`type`、`migration` 建立，不预先生成大量空包。
- 所有 EasyQuery 模块与 processor 由同一 `easy-query.version` 管理，当前基线 3.2.17。
- 普通运行依赖中不加入所有数据库驱动、测试框架、Spring、连接池或强制 JSON 序列化实现。

## 关键架构决定

1. HANA 通过 `DatabaseConfiguration` 注册实现，MySQL/PG 复用上游适配器；必要的增强安装采用服务组合。
2. 通用能力优先走既有 API。专属类型和能力显式选择，不让数据库类型泛型扩散到整个 Queryable/Join 链。
3. Java 值类型、SQL 存储类型、JDBC 绑定、表达式操作与 DDL/CAST 的渲染职责分开；详细契约见蓝图第 7、8 节。
4. 参数、别名、表访问、聚合和复制行为复用上游协议；禁止解析已渲染 SQL 后做字符串补丁。
5. Runtime 方言信息与增强注册按 client 隔离；不使用可变全局数据库状态。离线 SQL 生成与连接真实性检查分开。
6. 迁移实现复用现有命令模型，先生成/检查，后显式执行；不建立自动修改外部业务 schema 的后台过程。
7. Spring 接入优先现有 `StarterConfigurer` 或显式 Bean；需要同时处理 `optionConfigure` 和服务注册的生命周期，不能只复制一段 configure 调用后认为等价。
8. APT 继续使用上游 processor。必要的上游 SPI 变化须以复现和最小方案处理，不在本包中复制 APT 或修改上游私有状态。

## 代码审计入口

以下路径相对于相邻 `easy-query` 仓库，当前基线 commit 为 `2b25e9510868b4f9071365510393b0b02a451cda`：

| 入口 | 核对内容 |
| --- | --- |
| `sql-core/src/main/java/com/easy/query/core/bootstrapper/DatabaseConfiguration.java` | 服务配置与选项配置的两个生命周期 |
| `sql-db-support/sql-pgsql/src/main/java/com/easy/query/pgsql/config/PgSQLDatabaseConfiguration.java` | 现有数据库安装方式和 UUID handler |
| `sql-core/src/main/java/com/easy/query/core/expression/sql/expression/factory/ExpressionFactory.java` | 查询、写入、表、匿名查询、UNION、CTE 的生成入口 |
| `sql-core/src/main/java/com/easy/query/core/expression/sql/expression/impl/QuerySQLExpressionImpl.java` | 分页、普通/递归 CTE 与锁行为 |
| `sql-core/src/main/java/com/easy/query/core/func/SQLFunc.java` 及其子接口 | 完整通用函数面；不能只读一个接口后判断缺口 |
| `sql-core/src/main/java/com/easy/query/core/func/SQLFunction.java` | SQL 片段、参数、consume 与聚合分类 |
| `sql-core/src/main/java/com/easy/query/core/common/LikeFunctionTemplateGenerator.java` | 3.2.17 的 LIKE 转义与参数处理 |
| `sql-core/src/main/java/com/easy/query/core/util/EasyJdbcExecutorUtil.java`、`sql-core/src/main/java/com/easy/query/core/sql/DefaultJdbcSQLExecutor.java` | prepareStatement、generated keys、批处理与回填 |
| `sql-core/src/main/java/com/easy/query/core/migration/DatabaseMigrationProvider.java`、`MigrationEntityParser.java` | 元数据、DDL、索引/外键和同步命令 |
| `sql-platform/sql-api-proxy/src/main/java/com/easy/query/api/proxy/extension/window/OverExpression.java`、`NextSelector.java` | 已有窗口能力与 frame 扩展边界 |
| `sql-platform/sql-api-proxy/src/main/java/com/easy/query/core/proxy/PropTypeColumn.java` | 列与函数的桥接、结果映射和表上下文 |
| `sql-processor/src/main/java/com/easy/query/processor/ProxyGenerateProcessor.java`、`helper/PropertyColumn.java` | 固定属性类型映射与生成器限制 |
| `sql-extension/sql-springboot-starter/src/main/java/com/easy/query/sql/starter/SpringBootStarterBuilder.java` | 数据库选择及其后的 StarterConfigurer |

同一行缩写的文件名位于其对应模块与包目录；具体实现阶段记录完整文件与符号。完全注释掉的 `AbstractSQLColumnProxy`、`SQLStringColumnProxy`、`EntitySQLFunc` 不计为可用扩展点。

完整架构的验证不以模块能打包为止，必须满足蓝图第 12、15 节的消费者、组合、错误与真实数据库要求。
