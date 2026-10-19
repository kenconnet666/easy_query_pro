# EasyQuery Pro 实施计划

目标限定 MySQL、PostgreSQL、HANA，使用 Java 17，优先适配 HANA 2.0 本地部署。模块与依赖初始化已实施；后续功能设计见 [architecture.md](architecture.md)。

## 阶段 0 工程与运行环境

- 已建立父 POM、五个生产模块和一个不发布的测试模块，移除初始 Main 示例。
- 固定 EasyQuery 3.2.17，使用 Maven Central 发行依赖；相邻上游仓库保持不变。
- Maven Wrapper 固定 3.9.16，release=17。本机工具链使用用户指定的 `C:\Users\lionheart\.jdks\jbr-17.0.14`，不更改系统默认 Java。
- 已配置上游 APT，默认运行两组无数据库 SQL 契约；MySQL/PostgreSQL 的真实 JDBC profile 复用同一套 CRUD、值往返与事务检查。
- 已提供 Debian Docker 脚本和内存限制：MySQL 1 GiB、PostgreSQL 512 MiB；启动后验证实际 memory 与 memory+swap 数值，结束后清理本次资源。
- HANA profile 配好 SAP JDBC 驱动和只读连接检查，真实实例未配置；HANA 方言实现尚未开始。
- GitHub Actions 承担 Windows/Linux JDK 17 构建和数据库回归，保存测试报告与 JAR。实际运行状态以对应 commit 的 Actions 结果为准。
- IDEA MCP 已通过原生只读调用和构建检查。DebugTools 5.3.0 及热重载 MCP 可用，目前没有运行中的调试 JVM。

运行命令、凭据入口、资源清理和热重载边界见 [development.md](development.md)。

## 阶段 1 HANA 最小可用后端

1. 按上游 `DatabaseConfiguration`、`SQLKeyword`、`ExpressionFactory`、`SQLFunc` 接口接入。
2. 从无数据库 SQL 契约测试开始，覆盖标识符、schema、别名、JOIN、分页、参数顺序与常用函数。
3. 接入真实 HANA 2.0，验证基本 CRUD、事务提交/回滚、生成主键、批处理与常用 JDBC 类型。
4. 建立所有暴露能力的支持矩阵。尚未支持的递归 CTE、迁移或复杂写入必须有清晰的失败行为，不能输出其他数据库 SQL。

验收：同一组普通 EasyQuery 查询无需改 DSL 即可在声明支持的 HANA 功能范围内执行。每项结论记录增强包 commit、数据库 revision、驱动版本与对应测试。

## 阶段 2 补齐通用契约

- 时间、字符串、数值、聚合、JSON、窗口函数逐项做语义对照，重点覆盖 NULL、空结果、边界值、Unicode、日期精度和 decimal。
- 验证 HANA 的锁、分页组合、更新/删除限制以及并发事务行为。
- 通过现有接口补冲突写入和基础 Code First，未完成部分继续显式标记；DDL 只针对测试或应用自己的 schema。
- 对照 PostgreSQL/MySQL 等现有实现确定哪些是语法差异、哪些无法保持语义，不以最弱功能集替代准确契约。

验收：文档中的每个支持项对应测试；不能仅凭 SQL 字符串或其他数据库结果宣布 HANA 兼容。

## 阶段 3 方言类型 DSL 原型

- 在已建的通用 DSL、HANA/MySQL/PostgreSQL DSL 模块内实现功能。
- 先做窄范围 API 验证：一个 HANA 专属函数与一个 PostgreSQL JSONB 操作。方法名、入口形态和返回类型通过消费示例评审后固定。
- 类型正例：原始列、组合函数、select、where、orderBy、子查询中的使用。
- 类型负例：错库类型描述、参数类型不匹配、无效长度/精度；分别区分编译期拒绝与运行时拒绝。
- 查询上下文负例：把另一 client、另一方言或不兼容表上下文的表达式混入查询，执行前明确报错。
- 保留上游的参数绑定、表达式访问与结果映射，不拼接业务值，不解析 SQL 字符串恢复元数据。
- 若必须修改上游 APT 或公共查询泛型链，先提交独立设计与取舍，不在原型中悄悄扩大范围。

验收：仅靠上游公开/可维护的扩展点完成示例；普通查询写法不变；清楚列出编译期类型安全的边界。

## 阶段 4 有需求的通用增强与发布

- 优先评估显式窗口 frame，在既有窗口能力上补缺口，先证明 ROWS 的语义，再决定 RANGE 的跨库范围。
- 后续按实际用例考虑 HANA 全文检索、层级查询、JSONB 扩展等，不按数据库函数清单机械铺 API。
- 建立版本兼容表、模块使用示例、可运行的隔离消费工程和升级检查。
- Spring Boot 集成单独验证上游配置选择与生命周期，仅有明确需要时再创建集成模块。

验收：实际 JDK 17、真实数据库契约测试和发布产物消费测试全部通过；只声明已经验证的数据库/驱动/上游版本组合。

## 验证分层

| 层次 | 验证内容 | 执行方式 |
| --- | --- | --- |
| 快速本地 | SQL、参数、类型边界、失败路径 | 单元测试，不依赖数据库 |
| 消费与编译 | APT、泛型推导、错误用法、发行依赖 | 独立消费工程，用产物而非源码替代 |
| JDBC | CRUD、类型往返、事务、主键、批处理 | 真 HANA，固定并记录 revision 与驱动 |
| 跨库语义 | 通用函数、窗口与类型结果 | 同数据集在 HANA、PG 和相关参考库对比 |
| 并发与长期回归 | 锁等待、冲突更新、版本矩阵 | 有界测试与 CI；新 commit 须有自己的验证结果 |
