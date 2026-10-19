# 执行记录

## 目标与工作方式

2026-10-19 已获授权按完整蓝图无人值守推进，目标覆盖全部 23 项必需能力和 32 个工作单元。目标保持 active，不能以当前纵向原型代替完整成熟验收。上游仍为 EasyQuery 3.2.17 / `2b25e9510868b4f9071365510393b0b02a451cda`；编译和测试使用指定 JBR 17。

本地执行必要编译、IDE 检查和针对新增路径的短测试；全套三库和双平台验证交给 CI。提交后继续工作，下一次提交前检查上一 SHA 的 CI。依赖尚未验证的任务保持实施中，继续推进独立工作。

## 2026-10-19：HANA 基础与高风险主键路径

- G0.1 通过：已核对 clean 起点、上游 SHA、IDEA MCP、Docker 和基线 CI。
- G0.2/G0.3 实施中：已读 SQL expression factory、DML、SQLFunc 与 generated-key executor；完整函数审计、类型桥接和真实计算视图原型仍未完成。
- HANA 配置安装在既有 `DatabaseConfiguration` SPI，表达式工厂继承上游实现，只覆盖需要诊断或方言差异的入口。普通查询、绑定、CRUD、事务不新建执行器。
- `HanaSQLKeyword` 保持大小写、转义内嵌双引号；`HanaVersion` 解析数字 revision；`HanaDatabaseInfo.inspect` 显式验证连接，不使离线 `toSQL` 依赖 JDBC。
- 原生 HANA JDBC 2.30.11 不支持 `prepareStatement(sql, columnNames)`，也拒绝 `RETURN_GENERATED_KEYS`。已通过真实数据库测试保留该限制的证据。
- 主键回填通过 `HanaJdbcSQLExecutor` 对上游插入流程做窄范围扩展：逐行调用既有 insert，再在同一 `EasyConnection` 上读取 `CURRENT_IDENTITY_VALUE()` 或已注册 sequence 的 `CURRVAL`，复用原有 JDBC handler 与 setter。无回填的批量插入仍走上游批处理。
- 这是有明确成本的回填策略：每个需要回填的实体增加一次 SELECT，不能对 JDBC batch 结束后的一个 session 值做推算。仍需完成并发、连接池、回调中嵌套 SQL、触发器、非管理员与失败边界验证。
- `HanaSequenceGenerator` 使用上游 generator registry；不解析任意 SQL 来猜 sequence。未知自定义 generator 和重复 session key source 在写入前拒绝。
- 递归 WITH 明确拒绝；MERGE 冲突写入、joined DML、零普通列插入尚未实现，暂时明确拒绝，不能作为最终功能限制绕过蓝图。通用函数的完整 HANA 翻译也仍在实施范围内。

本地证据：

| 检查 | 结果与范围 |
| --- | --- |
| 默认 `mvnw verify` | 通过，5 个离线测试；三库测试源码全部编译且不连接数据库 |
| `tools/hana.ps1 -Action test -IntegrationTest 'HanaConnectionIT,HanaIdentityIT'` | 5 个 HANA 测试通过：连接身份、原生参数/事务、适配器 CRUD、驱动限制、单条及 1,003 条 identity 回填 |
| `tools/hana.ps1 -Action test -IntegrationTest 'HanaSequenceIT'` | 通过，真实 sequence、批量实体对应、显式回滚、写入失败后回滚及后续连接可用 |
| IDEA MCP build/diagnostics | JBR 17 编译通过；新生产源码无错误 |
| 清理 | 所有本次测试创建的表/sequence 通过 finally 删除；用户的 HANA 开发容器和持久卷保留 |

以上是本地范围证据，后续提交对应的 CI 结果需要单独记录。G1 和整体 H01–H04 不因这些原型通过而提前关闭。

## IDEA 源根与工具链修复

- `src/it/mysql/java`、`src/it/postgres/java`、`src/it/hana/java` 和驱动 test 依赖不再受 profile 激活控制；profile 仅控制 Failsafe 运行范围。
- 验证了 `mysql,postgres` effective POM 包含两个 pattern，没有 HANA pattern；默认构建不运行 IT。
- IDEA 对 `select-jdk-toolchain` 的配置存在解析差异，将 `discoverToolchains=false` 当成工具链要求。改为 Maven 标准 `toolchain` goal 与 `<toolchains><jdk><version>[17,18)</version>`。本地匹配到 `C:\Users\lionheart\.jdks\jbr-17.0.14`；CI setup-java 已确认会生成 toolchains.xml。
- 已通过 Computer Use 确认 Maven `Sync Finished`，重新打开旧编辑器后黄色源根提示消失；IDEA 编译通过。
- 提交 `e4a58efe31c10d89edebd2f9ab3c2e3d2e79260f` 的 CI [39266174287](https://github.com/kenconnet666/easy_query_pro/actions/runs/39266174287) 失败：提交中包含已引用新 HANA 类型的测试，但相应未跟踪源码遗漏。下一提交必须完整纳入该依赖闭包；不能把本地工作区通过当成该提交通过。

## IDEA 数据源与 HXE 租户修正

已通过 Computer Use 将 IDEA 的 HANA 驱动指向本地 ngdbc 2.30.11 JAR，并填写本机凭据。用户随后完成并命名四个 IDEA 连接：`HANA-SYS`、`HANA-HXE`、`MYSQL`、`POSTGRES`。实际 MCP SELECT 已返回 MySQL 8.4.11 / eqp、PostgreSQL 17.11 / eqp、HANA HXE / 2.00.088；没有将数据库凭据写入仓库。

MCP 核对发现原 39017 实际指向 SYSTEMDB，39041 才是 HXE 租户。已保留数据卷重建 HANA 端口映射，改正脚本与 CI 的 JDBC URL，并在连接测试中加入禁止 SYSTEMDB 的断言。本地在 HXE 重新执行 6 个 CRUD/identity/sequence 测试全部通过。提交 `b0fc93172e554d30571c8aea2e43eaeb79423b30` 的 [CI 39268845999](https://github.com/kenconnet666/easy_query_pro/actions/runs/39268845999) 全部强制任务通过，但它连接的是旧 SYSTEMDB，不能作为 HXE 租户验收证据。

MySQL/PostgreSQL 持久开发实例为 `eqp-dev-mysql-1` / `eqp-dev-postgres-1`，内存分别为 2 GiB / 1 GiB、无额外 swap，只绑定 127.0.0.1:53306 / 55432；容器和数据卷按开发环境保留。首次拉取的 TLS 超时已恢复，两个容器健康检查通过。密码复制脚本已用 Windows PowerShell 5.1 实际执行验证。

## 表达式桥接与 HANA 标量函数

- 新增 6 个跨三库离线互操作测试：显式构造的 String/Boolean 表达式复用上游类型接口，可接入已有函数、WHERE、排序、投影和 clone；构造布尔值本身不修改谓词。普通构建共 11 个测试通过。
- 注册 `HanaSQLFunc`，已覆盖任意参数数量拼接、COALESCE、indexOf 参数顺序、字符长度、LIKE 转义、当前时间/UTC 时间、随机数及有差异的数学函数。
- LIKE 复用上游的值/加密处理；在构造时转义私有副本，固定结果，避免重复渲染再转义。
- HXE 上 3 个 `HanaScalarFunctionsIT` 测试通过，覆盖真实 Proxy 执行、中文和参数绑定、NULL 传播、substring/indexOf、LIKE 通配字符、重复渲染以及负数向零截断。
- 函数清单见 [HANA 函数审计](hana-function-audit.md)。尚未覆盖的默认函数不能视为已支持；H08 与 G2.2 保持未完成。
- CI 已拆分 MySQL/PostgreSQL matrix；不等待新 CI 完整结束，下一次提交前读取上一提交的结果。

HDI 检查发现 HXE 尚未启用 diserver，因而没有 `_SYS_DI` 管理 API。已查 SAP HANA Platform 2.0 的官方启用步骤，后续需在测试 SYSTEMDB 为 HXE 启用该服务，再部署真实计算视图 fixture。参考：[按租户启用 HDI](https://help.sap.com/docs/SAP_HANA_PLATFORM/3823b0f33420468ba5f1cf7f59bd6bd9/6df24edbef3a4e039a7b0a7bf4acd6c8.html)。

## 下一步

1. 检查新提交的 HXE 租户/并行 CI，继续补 HANA 核心实现。
2. 为 HXE 配置可复现的 HDI 启用与真实计算视图 fixture，完成 G0 高风险验证。
3. 完成函数审计、类型桥接的转换器/编译负例与 SQL 组合失败覆盖，明确不等价能力与剩余风险。
4. 按依赖进入完整 HANA 函数/查询/类型实现，再实施通用 DSL 与三库类型增强。
