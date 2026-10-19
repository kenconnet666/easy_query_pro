# EasyQuery Pro

基于 EasyQuery 3.2.17 的 Java 17 增强包，目标数据库限定为 MySQL、PostgreSQL、SAP HANA。优先适配 HANA 2.0 本地部署，保持 EasyQuery 的统一查询体验；只有通用 API 无法准确表达的数据库能力才提供显式方言 DSL。

当前已建立 Maven 多模块、依赖管理、JDK 17 工具链、APT 与数据库验证入口。生产模块目前只有包声明，HANA 适配器和增强 DSL 尚未实现。

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
.\tools\hana.ps1 -Action start
.\tools\hana.ps1 -Action test
```

第一条不需要数据库，验证 JDK 17、上游 APT 和 MySQL/PostgreSQL 参数化 SQL。第二条通过 Debian Docker 启动隔离数据库，验证 CRUD 和事务，结束后删除本次容器、卷与网络。MySQL 内存上限为 2 GiB，PostgreSQL 为 1 GiB，均禁用额外 swap，并在测试前检查 Docker 实际配置。

HANA 使用独立 Docker 部署和持久卷，内存上限 12 GiB、无额外 swap。`start` 保留开发实例，`test` 运行真实 HANA JDBC 检查，`stop` 停止服务并保留数据；不与 MySQL/PostgreSQL 的临时测试清理混用。

完整验证交给 [GitHub Actions](.github/workflows/ci.yml)：Windows/Linux 构建、MySQL/PostgreSQL JDBC 契约、默认执行的 HANA Docker/JDBC 检查。另有手动验证外部 HANA 实例的入口。产物与测试报告保存为 Actions artifacts，尚不自动发布 Maven 包。

- [完整成熟交付蓝图](docs/blueprint.md)
- [通用 DSL 核心、数据库类型与方言清单](docs/dsl-design.md)
- [开发环境与运行方式](docs/development.md)
- [分包与设计方案](docs/architecture.md)
- [实施计划与验证条件](docs/plan.md)

源码基线：相邻 `easy-query` 仓库的 `3.2.17`，commit `2b25e9510868b4f9071365510393b0b02a451cda`。构建使用 Maven Central 的已发布依赖，不修改相邻源码仓库，也不要求先本地安装上游。
