# EasyQuery Pro

基于 EasyQuery 3.2.17 的 Java 17 增强包，目标数据库限定为 MySQL、PostgreSQL、SAP HANA。优先适配 HANA 2.0 本地部署，保持 EasyQuery 的统一查询体验；只有通用 API 无法准确表达的数据库能力才提供显式方言 DSL。

完整蓝图已获授权并进入实施。HANA 底层已开始实现：离线 SQL、普通 CRUD/事务、identity 与 sequence 回填已有本地真实数据库证据。完整函数、元数据和增强 DSL 仍在推进，当前阶段不能视为可发布产品；详细状态与限制见 [执行记录](docs/execution.md)。

| 子项目 | 职责 | 生产依赖 |
| --- | --- | --- |
| `easy-query-pro-hana` | HANA 底层适配 | `sql-core` |
| `easy-query-pro-dsl` | 通用表达式增强 | `sql-api-proxy` |
| `easy-query-pro-dsl-hana` | HANA 专属类型与操作 | 通用 DSL、HANA 适配 |
| `easy-query-pro-dsl-mysql` | MySQL 专属类型与操作 | 通用 DSL、上游 `sql-mysql` |
| `easy-query-pro-dsl-pgsql` | PostgreSQL 专属类型与操作 | 通用 DSL、上游 `sql-pgsql` |
| `integration-tests` | 编译、SQL、JDBC 验证，不发布 | 测试 scope 引用各模块 |

在仓库根目录运行：

```powershell
.\mvnw.cmd clean verify
.\tools\test-databases.ps1
.\tools\databases.ps1 -Action start
.\tools\hana.ps1 -Action start
.\tools\hana.ps1 -Action test
```

第一条不需要数据库，验证 JDK 17、上游 APT 和三库参数化 SQL。第二条通过 Debian Docker 启动隔离数据库，验证 CRUD 和事务，结束后删除本次容器、卷与网络。MySQL 内存上限为 2 GiB，PostgreSQL 为 1 GiB，均禁用额外 swap，并在测试前检查 Docker 实际配置。

`tools/databases.ps1` 启动供 IDEA 使用的 MySQL/PostgreSQL 持久开发实例，固定端口为 53306/55432，数据库及用户均为 `eqp`；`stop` 保留数据。它与临时测试的 Compose project、卷和清理流程独立。

HANA 使用独立 Docker 部署和持久卷，内存上限 12 GiB、无额外 swap。业务测试连接 HXE 租户的 `39041` 端口，`39017` 是 SYSTEMDB 管理端口。`start` 保留开发实例，`test` 运行真实 HANA 检查，`stop` 停止服务并保留数据；不与临时测试清理混用。

完整验证交给 [GitHub Actions](.github/workflows/ci.yml)：Windows/Linux 构建、MySQL/PostgreSQL JDBC 契约、默认执行的 HANA Docker/JDBC 检查。另有手动验证外部 HANA 实例的入口。产物与测试报告保存为 Actions artifacts，尚不自动发布 Maven 包。

- [完整成熟交付蓝图](docs/blueprint.md)
- [通用 DSL 核心、数据库类型与方言清单](docs/dsl-design.md)
- [统计、报表、开窗与复杂查询蓝图](docs/reporting-blueprint.md)
- [窗口规格、ROWS 与报表查询边界](docs/dsl-windows.md)
- [已实现的精确类型表达式及三库转换边界](docs/dsl-types.md)
- [三库原生数值目录、指数与精度边界](docs/native-numeric.md)
- [DSL 投影分类、三库分片与组合边界](docs/dsl-sharding.md)
- [派生表、CTE、UNION 与投影读取映射](docs/dsl-projections.md)
- [DSL 泛型挂载、默认工厂和扩展契约](docs/dsl-extensions.md)
- [NULL-safe 比较、NULLIF 与业务值绑定](docs/dsl-comparison.md)
- [数据库端日期截断及历法边界](docs/dsl-temporal.md)
- [结构化 JSON 路径、结果类型与 HANA 部署](docs/dsl-json.md)
- [PostgreSQL JSON/JSONB 原生视图与列映射](docs/pgsql-json.md)
- [MySQL JSON 原生视图、路径修改与列映射](docs/mysql-json.md)
- [MySQL unsigned 存储、参数与精确整数映射](docs/mysql-unsigned.md)
- [MySQL TIME 时长、微秒与连接池绑定](docs/mysql-duration.md)
- [HANA 受限 LOB 视图、切片与查询限制](docs/hana-lob-dsl.md)
- [HANA JSON 文本视图与存储容量](docs/hana-json-text.md)
- [HANA 字符类型、ALPHANUM 与 SHORTTEXT](docs/hana-characters.md)
- [PostgreSQL SQL 数组、元素类型与绑定边界](docs/pgsql-arrays.md)
- [PostgreSQL 日历间隔、精确映射与原生运算](docs/pgsql-interval.md)
- [开发环境与运行方式](docs/development.md)
- [分包与设计方案](docs/architecture.md)
- [实施计划与验证条件](docs/plan.md)
- [HANA 元数据、Code First 与事务边界](docs/hana-migrations.md)

源码基线：相邻 `easy-query` 仓库的 `3.2.17`，commit `2b25e9510868b4f9071365510393b0b02a451cda`。构建使用 Maven Central 的已发布依赖，不修改相邻源码仓库，也不要求先本地安装上游。
