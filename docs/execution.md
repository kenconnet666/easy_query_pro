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

HDI 检查发现 HXE 未启用 diserver，已按 SAP HANA Platform 2.0 的官方步骤启用。`infra/hana/enable-hdi.sql` / `.sh` 使本地 start 和 CI 可重复配置并等待租户 SQL API 就绪。

`HanaHdiIT` 已在真实 HXE 完成隔离容器创建、容器 API/Schema 授权、默认部署库配置、上传并 MAKE `.hdbtable` 与带输入参数的 `.hdbcalculationview`、绑定带单引号/中文的值查询，以及 finally 删除整个测试容器。过程使用顶层 JDBC CallableStatement，明确检查 HDI 返回码并保留精简诊断；未忽略错误或用普通视图替代。MCP 确认遗留的 `EQP_HDI_%` 容器为 0。详见 [HDI 测试说明](hana-hdi.md)。G0.3 的数据库部署路径得到验证，正式计算视图 DSL 仍属于 G5.4，未完成。

提交 `d6adb8cea5237bb48eb17bbf0547f83463dc1dd3` 的 [CI 39274828544](https://github.com/kenconnet666/easy_query_pro/actions/runs/39274828544) 已通过，涵盖修正后的 HXE 连接、并行 MySQL/PostgreSQL、双平台构建和当前标量函数；新的 HDI fixture 需要下一提交对应的 CI 验证。

## 2026-10-20：日期、CAST、JSON 与 Unicode

提交 `12a560fec059bd36987a78172cda41f95deef3b8` 的 [CI 39277882656](https://github.com/kenconnet666/easy_query_pro/actions/runs/39277882656) 已通过全部强制任务，包括真实 HDI 计算视图、HXE、MySQL/PostgreSQL 与 Windows/Linux。可选外部 HANA 未启用；此证据不用于声明以下尚未提交的改动已通过 CI。

- 按职责拆分日期运算、格式转换、CAST、JSON；字符串长度、索引与截取归入同一辅助类，删除仅承载长度翻译的独立文件。补充中文注释解释精度、JSON 包装和 JDBC 非直观行为。
- 日期加减支持常量和表达式，100ns 精度不可表示时明确拒绝；日期属性和完整单位差值保持上游编号及方向。数字格式 token、引号文本和原生格式分开处理。
- CAST 保留 Java 结果类型，长 NCLOB 转字符串不截断，Byte 读取不允许驱动静默回绕。
- JSON 标量/片段、存在性、数组索引和长度已接入原有 API。输出容量按 client 的不可变选项管理，区分 UTF-16 标量单位和片段字节；无效内容与容量不足报错。
- HANA 2.0 无法直接关联 `JSON_TABLE`，数组长度改用显式部署的 SQLScript 函数。库只生成部署 DDL，不自动修改 schema；这是已记录的运行前提，非管理员和迁移仍需继续验收。详见 [函数契约](hana-functions.md)。
- 补充字符的长度/截取/索引按码点对齐三库，公共 JDBC 契约增加相同用例，交由后续 CI 同时验证 MySQL/PG。

本地范围证据：

| 检查 | 结果 |
| --- | --- |
| `HanaDateTimeFunctionsIT` | 5 个测试通过，覆盖月末/闰日、精度、两种星期、负小数差值、格式/NULL/非法模式 |
| `HanaCastFunctionsIT,HanaJsonFunctionsIT` | 修复 Byte 回绕、JSON 容量单位后，8 个测试全部通过 |
| `HanaScalarFunctionsIT,HanaJsonFunctionsIT,HanaConnectionIT` | 最后一次执行 12 个测试通过；同次 11 个离线测试通过 |
| IDEA MCP | 新函数与 handler 检查；补齐上游 @NotNull 契约 |
| 清理 | MCP 查询 `EQP_JSON_SUPPORT_%` 遗留 schema 为 0；持久开发数据库保留 |

提交 `0e72ef9fd14212515faa37809a3bd5e14130f1db` 的 [CI 39286570474](https://github.com/kenconnet666/easy_query_pro/actions/runs/39286570474) 已通过全部强制任务，涵盖上述日期/CAST/JSON、补充字符，以及三库与双平台。以下新增的聚合和查询组合需要下一提交的 CI。

## 聚合、布尔投影与查询组合

- `HanaAggregateFunctionsIT` 6 个测试通过：基础/去重/空集聚合、GROUP BY/HAVING/条件聚合、STRING_AGG 分隔符/排序、忽略 NULL 的多列极值、八种窗口与默认 peer frame。
- `HanaScalarFunctionsIT` 扩展为 9 个测试，全部通过：增加左右补齐的码点边界、空字符串与纯空格的区分、基础字符串函数、18 个数学枚举和四则运算、保留三值逻辑的布尔投影。
- `HanaDateTimeFunctionsIT` 的 5 个测试再次通过，增加默认格式和原生格式绑定。
- `HanaQueryCompositionIT` 5 个测试通过：LEFT/RIGHT/INNER JOIN、相关子查询/EXISTS、UNION/UNION ALL、重复引用 CTE、分页和完整分组投影计数、NULL 排序。
- 组合测试暴露的 revision 88 关联 UNION 极值优化器错误已避开；CTE 自连接使用独立 Proxy/表引用。细节与已有上游 count 契约见 [函数说明](hana-functions.md)。

H08/G2.2 尚未关闭：去重字符串聚合需要查询级去重方案；少量旧 SQLFunc 工厂仍需独立组合证据。此次函数/查询测试使用派生表，没有创建持久数据库对象。

函数/查询组合提交为 `7af9e9bdd9820014fd634d937060c81a9818b946`，[CI 39289562420](https://github.com/kenconnet666/easy_query_pro/actions/runs/39289562420) 已启动；读取时仍在运行，不计作通过。随后单独整理测试设施：六个测试类统一使用 `HanaTestDatabase.client`，每次返回独立 client，保留选项隔离。整理后 5 个 JSON 集成测试与 11 个离线测试通过，IDEA 编译通过；不重复本地运行全套数据库测试。

后续 CI 39289562420 被新提交的并发策略取消。包含同一功能与测试整理的 `cde9e0812b956581b13017b11f968cf6c7bdf1bb` 已在 [CI 39289883670](https://github.com/kenconnet666/easy_query_pro/actions/runs/39289883670) 通过全部强制任务；取消的运行不计为通过。

## 生成键回调与触发器边界

真实复现发现旧回填路径存在数据正确性错误：INSERT after listener 在同一事务插入另一个 identity 表后，目标实体被填成 100，但实际目标主键是 1。已将需要回填的 INSERT 生命周期收拢到窄范围适配，复用上游绑定和类型设施，先读取并填键再发送 after 回调；内部键读取不触发可嵌套写入的用户 listener。

进一步的数据库触发器复现也得到错误键 100/101，而目标表为 1/2。当前通过准备阶段的结构化表目标传递与写入前触发器检查拒绝这一不安全自动回填；保留无回填和应用生成键的写入路径。没有采用 MAX(id)、解析 SQL 或缓存过期的安全结论。完整契约见 [生成键说明](hana-generated-keys.md)。

本地证据：

- 回调修复后，原有 identity/1,003 行回填、sequence 和嵌套 listener 的 4 个测试通过。
- 连接池并发与失败回调的 3 个测试通过：4 线程/2 连接，24 个键、18 行提交；失败后保留原始数据库异常及 suppressed listener 异常。
- 加入触发器保护后，identity/sequence/lifecycle/pool 共 7 个测试通过。
- 非管理员跨 schema 验证单独通过：仅授予目标表 SELECT/INSERT，验证预引用目标、触发器创建后拒绝且零误写、删除后同一 client 恢复回填。
- 最终增加基础表元数据校验，防止 view/synonym 绕过触发器检查；synonym 用例通过。一次 Windows JDBC bind 返回 `No buffer space available`，连接数检查未见耗尽；受影响的触发器用例及非管理员用例短重跑均通过，不将环境失败隐藏成首次通过。
- 表达式工厂调整后，4 个 lifecycle 和 5 个查询组合测试通过；最终 2 个短重跑与同次 11 个离线测试通过，IDEA 编译无问题。
- HikariCP 只增加 test 依赖；所有临时表、触发器、用户在 finally 清理，持久开发容器保留。
- 最终 MCP 查询临时用户、表和 synonym 均为 0；已删除本轮自建诊断日志，保留标准测试报告和构建缓存供后续工作使用。

G1.3/G2.4 保持实施中：长期压力、完整业务机制、非管理员 schema/迁移及其他写入组合仍待验收。本节证据不能替代新提交对应的 CI。

## 原子冲突写入与默认行调查

提交 `8d21fd258254e5caaddfbd70af9d1a4582e58d40` 的 [CI 39294562845](https://github.com/kenconnet666/easy_query_pro/actions/runs/39294562845) 已通过全部强制任务，包括新增回填生命周期、连接池和非管理员保护。

HANA `onConflictThen` 已接入原生 MERGE，复用上游插入列、参数与批处理。结构化执行目标同时用于唯一约束检查，要求显式冲突列集合对应实际主键或唯一索引；不先查业务数据，也不吞掉其他约束错误。生成值在 source 中只渲染一次，UPDATE/INSERT 引用别名。

本地 8 个 `HanaConflictWriteIT` 与同次 11 个离线测试通过，IDEA 编译及改动文件错误检查通过。覆盖行数、复合键、重复批量输入、选择列与 NULL、约束失败回滚、并发首次插入/已存在更新。临时表设施增加了资源作用域，统一负责本测试创建表的清理。

默认行调查确认上游在 renderer 前拒绝零插入列。更重要的是，当前 revision 88 的 identity override 空行探测触发真实 indexserver SIGSEGV，已停止重放并清理对象，记录脱敏 SQL/栈证据。不会把已知崩溃 SQL 交给默认 CI。详细行为与未完成项见 [HANA 写入契约](hana-writes.md)。G2.4 继续进行，零显式列路径、完整锁行为和 joined DML 仍未关闭。

MCP 最终确认本轮 MERGE/default/override 探测遗留表为 0。已删除本次事件生成的 10 个 crash trace/IPMM 文件和 2 个本地探测日志；标准测试报告及持久开发数据库保留。

## 行锁、隔离与连接初始化失败

提交 `fcdab2e4109a68076f013582b29d7bd44a106a61` 的 [CI 39298663389](https://github.com/kenconnet666/easy_query_pro/actions/runs/39298663389) 已通过全部强制任务，包含上述 8 个 MERGE 测试。

本轮复用上游 `forUpdate` 与事务管理，新增服务器锁等待证据、提交/回滚释放、三种隔离级别的跨连接快照可见性和池复用检查。原生 hdbsql 与 ngdbc 请求映射核对表明：revision 88 的监控视图会把 REPEATABLE READ 报告成 SERIALIZABLE，因此增加了行为验证而非修改隔离请求。

实测不支持的 READ_UNCOMMITTED 导致连接池保留 1 个活动连接，复现了上游 `DefaultEasyConnection` 初始化失败后的资源泄漏。新增 HANA 连接工厂在借用前拒绝不支持的隔离级别，并在任意初始化失败时归还已借出的整批连接；继续复用上游数据源选择、等待和事务包装。

本地 `HanaTransactionIT,HanaConnectionPoolIT` 共 8 个测试及 13 个离线测试通过。新增的 2 个连接工厂离线测试覆盖多连接中途失败和清理异常保留；IDEA 编译通过。重复连接池配置收拢到测试设施，作为独立整理提交，与连接工厂修复分开记录后一起推送，减少重复 CI 启动。

## HANA 目录与 Code First

提交 `3dee36c125fa84e1cddda618eede71f6f6a4e42c` 的 [CI 39301892874](https://github.com/kenconnet666/easy_query_pro/actions/runs/39301892874) 已通过全部强制任务，包含上一节的连接泄漏修复和事务回归。

新增只读 HANA 目录与完整的类型/DDL 纵向路径，复用上游 MigrationEntityParser、MigrationCommand、Code First 和 JDBC executor。修正上游生成器在重命名预览时读错表、漏传外键 action/schema、把 sequence 列建成 identity 的适配问题。表/索引先建、外键最后建；SQL 语句边界由结构保存，不按分号拆字符串。

迁移沿用上游事务，并通过 HANA 连接包装在提交/回滚后恢复 DDL 模式。真实测试证明回调里的普通 DML 与迁移共同回滚；已有事务的 DDL 模式不满足前提时在回调前拒绝，避免隐式提交业务写入。ngdbc 是可选生产依赖，只用于公开的 DDL 状态 getter。

本地证据：

- 最后一次 `HanaCatalogIT,HanaCodeFirstIT,HanaDdlTransactionIT` 共 9 个测试全部通过，同次 17 个离线测试通过；覆盖创建/幂等/重命名/扩宽/default/nullability/注释、真实外键级联、权限和失败恢复。
- 随后新增移除 ngdbc 的隔离类加载用例，`HanaMigrationTest` 共 5 个测试单独通过；不带 SAP 驱动仍可安装配置、解析类型并生成 DDL。IDEA 格式化后编译通过，检查无错误；保留 SQL 方言配置提示与反射访问测试 bean 的静态提示。
- 连接包装变化后，7 个 `HanaTransactionIT` 测试通过；非管理员用例先因测试授权 SQL 未引用区分大小写的 schema 而失败，修正后单独重跑通过，最终并入上述 9 个测试通过。
- native hdbsql 确认 BOOLEAN 默认值目录为 0/1、identity 生成表达式为空字符串；适配器已正确规范化比较。
- revision 88 的双引号列名 ALTER 在原生 SQL 中复现错误 257/事务内 129。该变体在计划阶段明确拒绝，保留创建/目录支持，不绕过依赖关系重建表。完整边界见 [迁移说明](hana-migrations.md)。
- IDEA 数据库 MCP 确认本轮 `EQP_DDL_%` / `EQP_CATALOG_%` schema、用户和 probe 表均为 0。持久开发数据库继续保留。

G3 保持实施中：复杂依赖迁移、完整类型与 Spring/独立消费者还需验收；本地结果不替代新 SHA 的完整 CI。

功能提交为 `a1c4b0410e4fe0c8eb83f7a3bbacd4484dea4157`。后续单独整理测试设施：三个非管理员场景共用测试用户/凭据/清理作用域，删除重复的 DataSource 配置和多层 finally；生成键权限与目录权限两个受影响用例通过，同次 18 个离线测试通过，IDEA 编译通过。两个提交一起推送以减少重复 CI；已删除本轮自建日志，标准测试报告和持久开发资源保留。

## 日期、UTC 与历法边界

提交 `bb9762abe033094b1cd81dbb072e6d8b004fbaec` 的 [CI 39325617092](https://github.com/kenconnet666/easy_query_pro/actions/runs/39325617092) 已通过全部强制任务，包括元数据、Code First 和测试设施整理。

随后继续类型验证，真实发现了两个日期偏移（柏林 DST 缺口 +1 小时、Apia 跳日 +1 天），并发现 Instant 被上游按需要无参构造器的 bean 处理。适配器补充公开 metadata 标量分类，使用明确 Calendar 绑定，并在原始 JDBC 结果进入行映射/分片聚合前保留逻辑时间值。库不修改 JVM 默认时区或上游私有字段；测试临时切换 JVM 时区后均在 finally 恢复。

当前本地 `HanaTemporalBindingIT,HanaDateTimeFunctionsIT,HanaCastFunctionsIT` 共 14 个测试通过，同次 18 个离线测试通过。涵盖 UTC Instant、实体批量写入/更新/Proxy 投影/NULL、精度拒绝、0001–9999 年与 HANA 历法边界、旧 Map 的不可表示值拒绝、上游内存行保真。完整多库分片、LOB 与转换器仍待实施。具体原因、原生 SQL 证据和接口契约见 [HANA 类型说明](hana-types.md)。

IDEA 格式化、编译与错误检查通过；数据库 MCP 确认 `EQP_TEMPORAL_%` 遗留 schema 为 0。本轮自建时间诊断日志已删除，标准测试报告与开发实例保留。

## LOB 与 UUID

提交 `d7a458d672521cb3d4da91969f75d077c8ef4c9f` 的 [CI 39329181887](https://github.com/kenconnet666/easy_query_pro/actions/runs/39329181887) 已通过全部强制任务，包含上一节的时间类型修复。

已验证 String/byte[] 大对象、文本 UUID 的原有 handler，并复现 SerialClob/SerialBlob 经通用 setObject 被写成对象字符串的问题。补充未注册具体 LOB 类的接口回退，保留显式 handler 优先级。进一步复现大 Clob 绑定在 execute 时出现 Stream closed；改用标准流 setter 和每次执行的资源作用域，使查询、插入、更新/原生 SQL 共享同一所有权规则，支持嵌套调用，不复制整个 LOB。

本地 `HanaLobIT,HanaIdentityIT` 共 6 个测试及同次 18 个离线测试通过。覆盖 >1 MiB BLOB、补充字符 NCLOB、ASCII CLOB、NULL/空值、UUID 条件/投影、批量失败回滚、提前结束/消费者异常、输入绑定/读取异常；后者验证调用方 LOB 未被 free、池归还和当前连接 M_ACTIVE_STATEMENTS 无增长。IDEA 格式化和编译通过。MCP 确认本轮 EQP_LOB_% 表/schema 为 0，已清理自建日志，保留开发实例和标准测试报告。具体规则见 [类型契约](hana-types.md)。

## 转换器与业务主键

提交 `495b941455a54d7e165ae47f9c2060e43b0f7bf7` 的 [CI 39333924655](https://github.com/kenconnet666/easy_query_pro/actions/runs/39333924655) 已通过全部强制任务，包括 LOB 生命周期与 UUID。

生成键路径复现 Long 直接写入业务 RowId 属性的 ClassCastException；现复用上游结果列映射与 fromValue，保留普通读取的解密/转换顺序。普通业务转换器、DTO、NULL、NCLOB、自定义 handler、两 client 隔离和 asStr 派生表达式已确认可复用上游，无需另造转换体系。

SQL 转换器测试还确认 HANA 会把 `? * 100` 的参数按 DECIMAL(19,0) 推断，19.99 存成 1900；显式 CAST 输入为 DECIMAL(19,2) 后，插入/查询/条件/更新均正确。保留正确的原有 INSERT 渲染，不在 SQL 字符串上打补丁。完整说明见 [类型契约](hana-types.md)。本地 `HanaConversionIT,HanaKeyLifecycleIT` 共 8 个测试及同次 18 个离线测试通过。

IDEA 编译通过，MCP 确认 EQP_CONVERT_% schema 遗留为 0；自建诊断日志已删除。后续继续依赖当前提交的完整 CI 验证，不阻塞等待。

## 导航关联 DML 与业务机制

提交 `9150c816b63cb50041251ee2b0875e900656a4d0` 的 [CI 39335227910](https://github.com/kenconnet666/easy_query_pro/actions/runs/39335227910) 已通过全部强制任务，包含业务主键回填及转换器。

关联更新使用一条 MERGE，避免 HANA UPDATE FROM 对同一行多次赋值的非确定性；原生重复匹配 689 和零误写已经独立验证。关联删除使用相关 EXISTS + SYS.DUMMY 保留 join 语义，不依赖目标主键。原有表/谓词/SET 节点和参数顺序继续复用，没有反向解析 SQL。

本地 `HanaBusinessIT` 的前 8 个测试通过，涵盖导航赋值、重复源、分组 LEFT JOIN、逻辑删除、复合键、乐观锁/回滚、租户/实体拦截器、include 和两 client 隔离；之后新增的无主键删除/别名冲突用例单独通过。同次 18 个离线测试通过。测试保留上游的物理删除授权、表达式版本号和普通导航 DML 使用 INNER JOIN 的规则，不为使测试通过而改写这些契约。更完整的边界见 [写入契约](hana-writes.md)。

IDEA 格式化和编译通过；MCP 确认 EQP_BUSINESS_% / EQP_JOIN_PROBE_% schema 遗留为 0。一次 hdbsql 清理命令因同一行包含两个语句而失败，已根据精确 schema 名重新清理并验证，未遗留探测对象；本轮自建日志删除，保留标准报告和持久实例。

## 精确 DECIMAL 表达式与类型负例

提交 `291d0e026a8719b455a401241a4c124bc64b043a` 的 [CI 39338105761](https://github.com/kenconnet666/easy_query_pro/actions/runs/39338105761) 已通过全部强制任务，包含关联 DML 与业务机制。

新增 client 隔离的增强安装、DDL/CAST 描述分离、`ProDsl.of` 的 DECIMAL typed value/NULL 与 `nativeCast`。结果继续使用上游 NumberTypeExpression；绑定、算术、谓词和 setter 均复用既有实现。当前只收敛这一条类型路径，完整 U01 和三库类型目录没有标为完成。

真实验证确认三库原生降 scale/溢出并不等价，因此保留原生行为的入口显式命名为 nativeCast。typed value 则预先校验精确表示，规范化 JDBC scale，避免隐式精度损失和极端 scale 扩展。完整规则与消费示例见 [类型表达式说明](dsl-types.md)。

组合测试复现“相关子查询尚未附着时误拒绝外层引用”，校验改为使用最终 SQL 的表上下文；不放开跨 client 使用，也不把表别名当身份。另一个真实失败是函数原始列路径绕过 SQL 属性转换器，现使用上游 EasyColumnSegmentUtil 构造属性值，再做 CAST；业务主键的 ValueConverter 不会重新套在数字输出上。

本地 `DecimalDslTest,DslCompilationTest` 共 18 项通过，后者运行 JDK 17 javac 验证 1 个正例和 4 个编译负例；HANA `HanaDecimalDslIT,HanaConversionIT` 共 4 项通过，同次 36 个离线测试通过。MySQL/PostgreSQL 通过共享 DecimalDslContract 验证真实绑定、NULL、关联子查询、克隆、原生舍入和溢出；IDEA 数据库 MCP 也独立核实了两个实例的原生 DECIMAL 结果。IDEA 格式化和编译通过。三库长期测试交由本次提交的 CI，不把上一 SHA 的绿色状态当作新功能验收。

最后一次 MySQL/PostgreSQL 运行包含极端零值 scale 归一化，共 2 个真实契约和 18 个选定离线测试通过。MCP 确认本轮 EQP_CONVERT_% schema 遗留为 0；自建三份诊断日志已删除，标准测试报告和持久开发数据库保留。

## NULL-safe 比较

提交 `dd3c73e23b5ff9cd3b798630ffad7b7bb6f40885` 的 [CI 39341595785](https://github.com/kenconnet666/easy_query_pro/actions/runs/39341595785) 已通过全部五个强制任务，包含三库真实 DECIMAL、类型负例和转换器组合。

新增 U04 的列/表达式比较与业务值比较，复用已验证的函数/表作用域。MySQL 和 PG 使用原生 NULL-safe 操作符；HANA 的 MAP 经独立 MCP 探测与真实契约确认两端 NULL 匹配，模板中每个输入只出现一次。布尔结果继续使用原有 BooleanTypeExpression，构造不暗中添加条件。

业务值参数保留列元数据并走上游比较值链；不手工调用转换器后再重复绑定。HANA 的 Label、SQL 属性转换与 NULL 已执行。消费者编译复现 Java 对同名“表达式/值”重载传 null 的歧义，因此值入口使用明确的 Value 后缀。此调整在发布前完成，并纳入编译正例，避免把歧义留给使用者。

本地 HANA `HanaNullSafeDslIT,HanaConversionIT` 共 4 项通过，同次 43 个离线测试通过；此前 HANA DECIMAL 与初始 NULL-safe 两个契约也通过。MySQL/PostgreSQL 共享真实契约共 2 项通过，同次 25 个选定离线测试通过；最后增加的 isDistinctFromValue(NULL) 用例已由这两库本地验证，HANA 交由本次 CI 验证。`DslCompilationTest` 现在覆盖 3 个正例、6 个负例，连同 17 个 DECIMAL 和 6 个 NULL-safe 离线用例构成选定检查集。使用说明见 [NULL-safe 比较](dsl-comparison.md)。

IDEA 格式化和编译通过，MCP 确认本轮 EQP_CONVERT_% schema 遗留为 0；自建诊断日志已清理，保留持久开发数据库与标准报告。完整 U04 验收仍包含后续分片/类型目录组合，G4.3 的 NULLIF、日期截断和 JSON path 继续推进。

## NULLIF 类型族与可空结果

提交 `51c1d436f2b03be685ee2813309e6bfc84453665` 的 [CI 39342797479](https://github.com/kenconnet666/easy_query_pro/actions/runs/39342797479) 已通过全部五个强制任务，包含上节 NULL-safe 比较和完整三库回归。

U05 使用原有数字、文本、日期时间、布尔类型族重载，返回相应上游表达式；`nullIfValue` 与表达式重载分开命名，不要求强转 null。参数绑定复用 U04 的上游比较参数路径。普通列保留结果映射，显式 asStr 等视图的输出移除原整列转换器；nullable 数字与布尔结果不使用 primitive 默认值。

本地 HANA `HanaNullIfDslIT,HanaConversionIT` 共 4 项通过，同次 50 个离线测试通过。MySQL/PostgreSQL 的共享真实契约共 2 项通过，同次 9 项选定离线/编译检查通过；两库验证包含新增 primitive 属性投影为 NULL 的边界。JDK 17 消费者编译现覆盖 5 个正例、9 个负例，验证数字/文本/日期/布尔结果类型及混用错误。IDEA 格式化和编译通过，MCP 确认 EQP_CONVERT_% schema 遗留为 0。

最后单独运行 HANA `HanaNullIfDslIT`，确认 primitive 投影边界通过；同次 50 个离线测试通过。本轮三份诊断日志已删除，标准报告与持久数据库保留。

三库均使用原生 NULLIF。MySQL 在不相等时可能重复求值左值，这是明确记录的原生限制；不为随机值/序列制造跨库单次求值保证。完整使用规则见 [比较与 NULLIF](dsl-comparison.md)。日期截断、方言类型与跨分片结果合并继续推进，不以本节替代 G4/G7 的完整验收。

## 日期截断与测试设施整理

提交 `26ad1ee4b0f223ed33560a92ba9cddc77f5416cc` 的 [CI 39343820002](https://github.com/kenconnet666/easy_query_pro/actions/runs/39343820002) 已通过全部五个强制任务，包含 NULLIF 四种标量族和 primitive 可空结果。

先以独立提交 `f781eed801b27dba12dea8506982a1450c2fc1c1` 合并三个 DSL 契约和转换器测试的 client 配置，复用已有 TestClients；29 个相关离线测试通过，所有调用点调整后 IDEA 编译通过。该整理与后续日期功能一起推送，避免无意义地取消正在执行的 HANA CI。

U06 的 LocalDate/LocalDateTime 入口通过明确的日期粒度校验与静态输入类型限制，不自动接收 Instant。三库各自提供翻译，继续返回 DATE/TIMESTAMP。MySQL/HANA 多次引用一个参数时使用独立模板位置，对所有粒度核对 SQL 问号数量、实际绑定数量及克隆稳定性。

本地初次 HANA 契约和同次 57 个离线测试通过，随后 MySQL/PostgreSQL 共同契约共 2 项、10 项选定离线/编译检查通过。边界探测发现 HANA 用日号减法求月初会跨过 1582 年历法缺口：1582-10-18 减 17 天得到 1582-09-21。月/季度改为年月坐标计算，独立 MCP 确认得到正确的 1582-10-01。

最终一次本地三库一起执行，共 3 项真实契约、10 项选定离线/编译检查全部通过，包含新加的 1582-10-18、ISO 周跨年、闰日、季度/月末、小数秒、NULL、重复参数、克隆与过滤。IDEA 格式化、编译及关键文件检查通过。本轮仅使用已有数据库做 SELECT；自建三份诊断日志清理，标准报告与开发实例保留。

历史星期的跨库差异仍未解决：1500-02-28 在 HANA 与 PostgreSQL 分别为星期五/星期三。当前共同范围、使用前提和未关闭的统一拒绝/表示问题见 [日期截断说明](dsl-temporal.md)，未将现代日期通过视为完整历史历法支持或整个 U06/G4.3 完成。

## 复用上游的模板绑定机制

提交 `5d727fec2d7609d5661c9f216759a37914a80d6b` 的 [CI 39345784820](https://github.com/kenconnet666/easy_query_pro/actions/runs/39345784820) 已通过全部五个强制任务，包含日期截断、历法切换后的边界和测试设施整理。

进一步审计重复占位符时，最初仅看到 MessageFormat 调用而怀疑参数会少绑定；追到 `EasySQLExpressionUtil.parseParamExpression` 与 `SQLFormatArgument` 后确认，上游在每次占位符出现时才渲染并绑定，已经支持重复和重排。四个真实 HANA 组合用例证明现有实现正确：嵌套 Unicode 字符串、带绑定日期运算的格式化及花括号字面量、两个绑定时间的反向 duration、带绑定输入和分隔符的 STRING_AGG。

因此未给 HANA 函数添加重复的模板展开器。反而删除新日期实现中手工维护的引用次数与中间 Template，方言只提供固定模板，共同 DSL 继续用上游 SQLFunction 绑定。当前日期文档也改为描述实际复用机制，而非要求每次引用手工分配编号。

本地先运行 4 个组合测试通过，再运行 `HanaFunctionCompositionIT,HanaTemporalTruncationDslIT` 共 5 项通过，同次 57 个离线测试通过；三库的参数数量/次序与克隆离线契约保持通过。IDEA 格式化和编译通过；数据库用例仅做 SELECT，没有新建数据库资源。两份诊断日志清理，保留标准报告。MySQL/PG 的完整真实回归由本次 CI 继续执行。

## 有界 Unicode 文本类型

提交 `9f19a92dc8c0fa244c2a51ec6952b6b7bde9f8c7` 的 [CI 39347285886](https://github.com/kenconnet666/easy_query_pro/actions/runs/39347285886) 已通过全部五个强制任务，包含上游模板复用及新增函数组合。

新增 TextType 与 CommonTypes.unicodeText，返回既有 StringTypeExpression。容量明确为 1–5000 个 UTF-16 单元，typed value 预先拒绝超长、NUL 与未配对代理项，保留空串/NULL。DDL 与 CAST 分别渲染；尤其 MySQL 使用 VARCHAR DDL、CHAR CAST 和显式 utf8mb4。原生 CAST 不假装具有相同的截断规则：MCP 与真实契约都确认长度 2 的 `😀a` 在 HANA 输出 `😀`，在 MySQL/PG 输出原值。

三个已有 JdbcContract fixture 改用该类型描述创建真实列，删除已无人调用的默认 VARCHAR 重载。DDL 声明只表示物理类型，不会自动给其他实体写入增加 UTF-16 校验或 CHECK 约束；完整边界已写入 [类型表达式说明](dsl-types.md)。没有给 String.class 安装全局 JSON/字符转换器。

本地 HANA `HanaTextDslIT,HanaConnectionIT` 共 4 项通过，同次 62 个离线测试通过；MySQL/PostgreSQL 共享契约共 2 项通过，同次 9 项选定离线/编译检查通过。消费者编译现有 7 个正例、14 个负例。IDEA 格式化、编译和关键文件检查通过；MCP 确认 HANA/MySQL 本轮 fixture 表遗留为 0，两份诊断日志清理，保留标准报告与持久数据库。

## 结构化 JSON 路径与结果类型

提交 `f0647c229019e261f4a3456ee43da2f34054d340` 的 [CI 39349207501](https://github.com/kenconnet666/easy_query_pro/actions/runs/39349207501) 已通过全部五个强制任务，包含有界 Unicode 类型及三库 DDL/CAST。

新增不可变 JsonPath（字面 key/非负 index，最多 100 段）、JsonFragmentExpression，以及 jsonAt/jsonTextAt/jsonBooleanAt/jsonExists。JSON 片段需要显式 asText 才能用字符串方法；存在性、JSON null、SQL NULL、缺失、布尔类型与父节点不匹配都有独立契约。三库实现复用上游 SQLFunction、绑定与结果类型，不替换整套 SQLFunc，也不新建查询/事务执行器。

原生探测确认 HANA 路径不能绑定（257），顶层标量不能直接作为原生 JSON 根（293），且路径有隐式展开差异。新增显式部署的 EQP_JSON_DOCUMENT，以 SQLScript NCLOB 参数和原生 JSON_TABLE 检查单值并筛选种类；原有 HANA 成员转义、JSON_QUERY/JSON_VALUE 与容量逻辑继续复用。HanaOptions 统一注册成当前 client 的不可变服务，避免基础层与增强层配置不同。

MySQL 前缀类型检查抑制自动数组包装；PostgreSQL 经 JSON 类型读取，避免 JSONB 标量原生 `-> 0` 返回自身，并保留 JSON/JSONB 的区别。额外的 NO_BACKSLASH_ESCAPES 用例复现 Connector/J 客户端预处理把字符串变成 binary 十六进制的行为；文档和路径显式转 utf8mb4 后，两种预处理模式均通过，未更改应用会话模式或全局 String handler。

本地初次 HANA 辅助函数与路径共 3 项通过，同次 71 个离线测试通过；PostgreSQL 的公共契约与 JSONB 根值专测共 2 项通过。修复编码后，MySQL 公共契约和两种预处理模式共 3 项、8 项离线测试通过。最后单独 HANA 路径契约再跑通过，同次 71 个离线测试通过。新加的字面文档参数案例由 MySQL/HANA 本地和后续 PostgreSQL CI 验证。javac 消费者检查现为 8 个正例、16 个负例。

IDEA 格式化、编译和关键文件检查通过；MCP 确认本轮 HANA JSON 探测/文档/种类/路径 schema 与 PostgreSQL fixture schema 遗留均为 0。探测 Java 文件及本轮五份诊断日志已清理；标准报告与开发实例保留。HANA parser 的非标准宽松输入、输出容量、显式部署和性能边界见 [JSON 路径说明](dsl-json.md)。原生 MySQL JSON/PG JSONB 修改和完整性能/分片矩阵继续实施。

## 表达式作用域整理

提交 `e2d6a6447d94551c76b0037b587a6db4213777bf` 的 [CI 39358012526](https://github.com/kenconnet666/easy_query_pro/actions/runs/39358012526) 已通过全部五个强制任务，包含 JSON 路径、HANA 辅助函数和 MySQL 编码模式回归。

将 client 身份、原有 SQL 属性/参数转换、最终表归属和派生分片保护移入内部 ExpressionScope，公共 ProDsl 保留操作入口和返回类型。后续原生类型视图直接复用这层规则，避免各模块重新实现自己的上下文检查。47 个相关离线 SQL 契约通过，IDEA 格式化和编译通过；本次诊断日志清理，未创建数据库资源。

## PostgreSQL JSON/JSONB 原生类型

JSON 与 JSONB 使用不同的描述和返回接口；JSON 通过显式 toJsonb 才获得 JSONB 方法。加入 JsonInput 区分已编码 JSON、JSON null 与 SQL NULL。NativeJsonColumnConverter、JsonExpression 和 Unicode 检查复用共同基础，PG 没有另建执行器、事务或序列化框架。

已实现包含、key/任意 key/全部 key、原生 jsonpath、jsonb_set、key/path 删除；数组路径使用原生 text[] 语义。业务值对象在 view 与 SQL 修改后保留原 Java 类型。known 内置声明冲突在构造时拒绝；未知 domain/计算表达式不伪称已经查过真实 schema。

实库集成复现并澄清了上游绑定边界：列注解的 JdbcTypeHandler 不决定 3.2.17 的写入 handler，写入实际按序列化后的 Java Class 选择。删除了无效的专用 handler，改用列 SQL 转换 SPI 为 JSON/JSONB 参数生成 CAST，保留原 ValueConverter 与 String handler。配置注册在首次实体元数据使用前显式执行，普通 String 字段不受影响。

本地 PostgresJsonDslIT 的 4 个存储/转换/原生操作/业务对象与回滚用例通过，同次 14 个选定离线测试通过。之后新增 GIN 检查单独通过，同时 9 个离线/消费者编译用例通过；其中 EXPLAIN 暂时关闭单连接顺序扫描，仅验证包含、key、jsonpath operator 能用 GIN，不作为性能结论。消费者检查现为 10 个正例、19 个负例。

IDEA 格式化、编译和关键文件检查通过；MCP 确认 eqp_json_* fixture schema 遗留为 0。三份诊断日志清理，保留标准报告与开发实例。说明见 [PostgreSQL JSON/JSONB](pgsql-json.md)。本次与此前独立的 ExpressionScope 整理提交一起推送，完整三库/平台回归交给当前 CI；MySQL 原生 JSON 与 PG 数组/interval 等继续推进。

## MySQL JSON 原生类型

提交 `d12167248503a7050ed8ca78fbfc98f9f7eef998` 的 [CI 39364347947](https://github.com/kenconnet666/easy_query_pro/actions/runs/39364347947) 已通过全部五个强制任务，包括 PostgreSQL JSON/JSONB 与完整 HANA 回归。显式方言检查、共享 JSON 业务 fixture/MySQL 测试数据源分别整理为独立提交。

MySqlTypes.json/MySqlDsl 复用 NativeJsonType、AbstractJsonExpression、ExpressionScope 与列 SQL 转换 SPI。实现选定的包含、单/多路径存在、按顺序修改及删除；通过 MySqlJsonChange 序列保留重复路径和父子先后关系。JSON 文档与路径显式按 utf8mb4 绑定，业务结果保留 T，普通 String handler 不变。

MySQL 专属方法保留原生 NULL、数组自动包装和缺失父节点规则，公共读取仍采用 JsonPath 的严格导航。离线错误库测试最初因错误 fixture 的未注册转换器先失败，改为使用无方言映射的基础实体，确保测试真正到达方言检查。

本地 4 组 MySqlJsonDslIT 通过，覆盖普通/NO_BACKSLASH_ESCAPES 和客户端/服务端预处理的笛卡尔组合；同次 10 个离线/编译测试通过。业务对象写入与 SQL 内修改、无重复序列化、回滚、双重数组删除、父子顺序修改、绑定中英文/补充字符/引号/反斜杠及 SQL NULL/JSON null 均有真实证据。消费者编译现在含 12 个正例和 23 个负例。

IDEA 格式化和构建通过；批量格式化曾被 IDE 的新增文件 Git 提示阻塞，已通过电脑控制关闭提示并确认执行完成。MCP 确认本轮 eqp_mysql_json_* 测试表遗留为 0，自建诊断日志清理，持久数据库与标准测试报告保留。

使用与限制见 [MySQL JSON](mysql-json.md)。继续由 CI 执行三库与平台完整回归，PG 数组/interval 和后续 G4–G7 尚未完成。

## PostgreSQL 一维 SQL 数组

提交 `3de6c2e4b79bbae214807c3b9b49a5edc399faec` 的 [CI 39366533402](https://github.com/kenconnet666/easy_query_pro/actions/runs/39366533402) 已通过全部五个强制任务，包含 MySQL JSON 的四种模式、测试设施整理和完整三库回归。

新增 PgArrayType/PgArrayValue 与 pgJDBC 专用 handler，仅注册显式包装类，不改变普通 List/String/byte[]。支持规划中的八种元素类型、一维和默认下界。PgArrayExpression<E,T> 保留元素与整列映射的两个类型；ANY、包含/被包含、重叠、cardinality、维度长度和 SQL 下标访问回到准确的上游表达式族。

核查 42.7.14 源码后发现 getArray/getResultSet，以及二进制 PgArray.toString 会丢弃下界，因此读取先检查公开二进制头或文本前缀，拒绝多维/非默认下界。没有解析完整数组文本或读取私有字段。时间参数复用 pgJDBC TimestampUtils，真实验证包含公元前、1500 年日期、补充字符、NULL 元素和 infinity。EasyQuery 不把自定义包装类当作基础类型，使用公开 EntityMetadataManager 扩展点补充精确的 PgArrayValue 标量元数据。

本地先通过 5 项数组映射/批处理/维度边界实库用例，然后通过含 DSL 的 8 项组合。最后新增 GIN 可用性及显式 int4[] → int8[] 转换后，PostgresArrayDslIT 共 4 项通过。另有 15 个选定离线/编译用例通过，包括读取/绑定主异常与 cleanup 异常的保留、跨 client 拒绝，以及 14 个消费者正例、28 个负例。GIN 只证明包含/重叠 operator 能匹配索引，不作为性能结论。

IDEA 构建通过，MCP 确认 eqp_array_* fixture schema 遗留为 0；临时表随所属连接关闭，开发数据库保留。MySQL Draft 投影的 IDE 泛型提示整理为独立提交 `5eaa032`。本轮数组编译/原生/离线/索引诊断日志在记录证据后清理，标准报告保留。使用说明见 [PostgreSQL 数组](pgsql-arrays.md)。

## PostgreSQL 日历间隔与精确读取

提交 `5bbf90b92cc9600cc449e88fe282d7bd7abe1824` 的 [CI 39368313313](https://github.com/kenconnet666/easy_query_pro/actions/runs/39368313313) 已通过全部五个强制任务，包含数组类型、业务 List、下界、资源释放和三库完整回归。IDEA 意外生成的一份与源文件内容相同的 java~ 备份已在独立整理提交 `22ae495` 中删除，并加入忽略规则。

PgInterval 使用 Java 17 sealed 类型区分精确有限三分量与正负 infinity。月份/天数/时间保持独立；固定 Duration 转换只允许月份和天数为零。原生 DSL 提供加减/取反、比较，以及 LocalDate/LocalDateTime 的日历加减；日期运算结果明确为 LocalDateTime。实际证明 1 month 和 30 days 在 SQL 比较可相等，但加到 2024-01-31 得到不同日期。

实库探测复现 pgJDBC 42.7.14 的超大小时 int 溢出，以及官方已记录的 sql_standard 字段丢失。因此通过公开 getString 读取四种服务器输出格式，用 BigInteger/BigDecimal 精确恢复分量，不复制任意文本输入解析或修改连接 IntervalStyle。进一步强制 interval 二进制传输，发现 getString 对有效非 NULL 值也返回 null；用 wasNull 判断后再读取公开 getBytes 的 16 字节表示，修复该静默丢值路径。两种协议均覆盖分量极值及无限哨兵。

本地 8 组输出格式/预处理映射最先通过；DSL 的 3 个真实用例与 13 个离线/消费者编译用例随后通过。补充二进制路径后，12 组映射和 5 个选定离线检查通过。消费者编译目前为 15 个正例、32 个负例。MCP 确认 eqp_array/eqp_interval 本轮 schema 遗留为 0，JDBC 探测连接均已关闭。

数组标量元数据整理为内部 PgScalarMetadataManager，为两类显式 PG 值复用；普通 Java 类型的默认映射不变。IDEA 构建通过；探测源文件和本轮三份诊断日志记录证据后清理，标准报告保留。说明见 [PostgreSQL interval](pgsql-interval.md)。完整原生类型目录、时区类型、组合和最终性能/消费者门槛继续执行。

## PostgreSQL fixture 整理与 MySQL unsigned

提交 `031a455d90d9d1fad0c63b3a510f6320f7f690f3` 的 [CI 39369679147](https://github.com/kenconnet666/easy_query_pro/actions/runs/39369679147) 已通过全部五个强制任务，包含 interval 四种格式、二进制及三库完整回归。

独立提交 `515ff14` 将三组 PG fixture 的 schema 创建/销毁收拢到 PostgresTestDatabase.Schema，异常路径关闭连接，清理前回滚未完成事务。12 个相关实库用例和 3 个离线用例通过；该整理与功能分开提交。

MySqlTypes 提供 TINYINT/SMALLINT/MEDIUMINT/INT/BIGINT 五种 unsigned 存储描述、适合完整值域的 Java 类型和参数范围检查。仅 unsignedBigint 实现 NumericType/CastTarget，因为 MySQL 原生 UNSIGNED CAST 固定为 64 位；较小宽度通过 MySqlDsl.value 生成经过校验的数字参数，其他运算复用上游表达式。

原生测试证明 EasyQuery 3.2.17 对 BigInteger 的默认 Object handler 会将 SUM 的 DECIMAL 原样返回 BigDecimal，因此新增精确整数读取。初次尝试 setBigDecimal 写入导致服务端预处理将最大 unsigned 值截至 Long.MAX_VALUE；独立 JDBC 对比确认原有 setObject(BigInteger) 可保留完整 unsigned 标志。最终 handler 直接继承 ObjectTypeHandler，仅覆盖 getValue，不替换已正确的绑定。

本地最初 12 个离线/消费者编译检查通过，最终修复后 MySqlUnsignedIT 的 4 组预处理/减法模式组合和 3 个离线检查通过。覆盖五种物理列上限、零/NULL、BigInteger/SUM、非整数拒绝、strict 写入越界保持原值、CAST 负数和 NO_UNSIGNED_SUBTRACTION。消费者编译现为 16 个正例、35 个负例。

IDEA 构建通过，MCP 确认 eqp_unsigned_* 表遗留为 0。IDEA 的新文件加入 Git 提示再次阻塞工具，已在界面取消重复提示，后续由显式 git add 管理暂存。JDBC 探测文件与本轮编译/原生日志记录后清理，标准报告和开发数据库保留。说明见 [MySQL unsigned](mysql-unsigned.md)。

## 计算值投影映射与 MySQL TIME 时长

提交 `774c0c801d3054c5532da58c0b5d918780c0f644` 的 [CI 39371105860](https://github.com/kenconnet666/easy_query_pro/actions/runs/39371105860) 已通过全部五个强制任务，包含 unsigned 和 PG fixture 整理。

独立修复提交 `90e166f` 收拢完整值表达式的读取映射。EasyQuery Draft 仅为 SQLColumn 自动带入列读取器，计算表达式会走基础类型；同时 APT 的 Object.class 载体不能随意换成业务 record/List 类，否则 selectColumn 会错误尝试实例化新实体。MappedValueExpression 保留原载体，通过公开 ColumnReaderImpl/结果转换 SPI 为 Draft 恢复原列 handler 与 ValueConverter。PG JSON/数组/interval 共 14 个实库用例、MySQL JSON 四种模式和克隆已通过，未添加新的结果集执行器。

MySqlTypes.durationTime(fsp) 与 MySqlDurationExpression 使用 Duration，保留负值、超过 24 小时和微秒。原生 plus/minus/negated、比较、日期时间组合以及精确 toSeconds 均复用现有表达式/绑定链；TIME_TO_SEC 的小数丢失与 div_precision_increment 的舍入通过带符号微秒乘法解决。输入容量/精度检查与原生计算饱和分别处理，LocalTime 的钟点映射不变。

ORM 插入复现 Connector/J 9.7.0 客户端 Duration 编码残留 S 的问题；仅修零值不足以覆盖负微秒，因此客户端统一绑定规范 TIME 文本。独立探测也确认服务端 CAST 前把字符串参数转换成零，最终通过 JDBC wrapper API 识别实际 ServerPreparedStatement，保留其二进制 Duration 绑定。强制 String + Types.TIME 的替代方案在驱动内发生 ClassCastException，未采用。该判断不读取驱动私有字段。

本地 MySqlDurationIT 扩展到两种预处理 × 直接/Hikari 两种连接，并开启批量重写，四组全部通过；同次 JSON/unsigned 合计 12 个实库用例及 4 个离线检查通过。随后新增精确秒数 SUM 的四组 Duration 用例单独通过。初期消费者编译检查通过，目前共 17 个正例、38 个负例。完整值 Draft、标量和业务对象转换的回归单独记录在前述修复提交中。

独立整理提交 `5f3ce39` 将三组 MySQL fixture 的表生命周期收拢到 MySqlTestDatabase.Table。MCP 确认本轮三类 MySQL 表和 PG JSON/数组/interval schema 遗留均为 0。IDEA 构建通过；两份 Duration 探测源文件及本轮编译/原生/聚合/映射诊断日志记录后清理，标准报告保留。说明见 [MySQL TIME 时长](mysql-duration.md)。

## HANA 受限 LOB 与返回类型标记

提交 `69e22f25fb4094ac9ebb88e0b71b371e5a1de747` 的 [CI 39374888549](https://github.com/kenconnet666/easy_query_pro/actions/runs/39374888549) 已通过全部五个强制任务，包含公共投影修复、MySQL 表生命周期整理与 Duration 的连接池/批量组合。

HanaTypes/HanaDsl 新增 CLOB/NCLOB/TEXT/BLOB 的明确视图。NULL、UTF-16/字节长度、受限切片和完整标量文本转换进入现有表达式链；TEXT 仅作为存储类型。HANA 实测 TO_NVARCHAR(NCLOB) 保留 6004 单元并允许排序，BLOB → VARBINARY 直接 CAST 报 266，而 SUBSTR 返回 VARBINARY。NCLOB 切片可拆开代理项，因此 API 明确使用 UTF-16 单元，未假称按 code point 切片。

通过公开 SQLSegmentFactory、SQLFunction 和 TableVisitor 保留已知 LOB 的返回类型。DISTINCT 对当前投影验证；函数分组通过公开 visitor 读取返回类型，不检查标量表达式内部的 LOB 操作数，因此显式标量转换不被误拒绝。克隆保留返回标记，clone 后新增 DISTINCT 会失败且原查询仍可使用。基础选择接口继承 asc/desc，不能用编译负例伪称它们不存在；改为在构造排序时拒绝，并补充条件关闭/NULLS 模式的检查。

通用比较通过 ScalarComparisonRestriction 保留 JSON 的原有限制，并允许 HANA LOB 明确要求标量转换。byte[] 标量通过公开 metadata 扩展点补入；3.2.17 APT 的 byte[] → Byte[] 问题通过已存在的 asAnyType(byte[].class) 校正，没有 fork processor 或修改上游私有字段。

本地最终 23 个离线/消费者编译检查和 7 个 HANA 实库用例通过，包含新 LOB 视图与原有大对象、流式、失败清理回归；消费者检查现在为 18 个正例、43 个负例。最后补齐直接 Selector 接口的返回标记后，2 个查询限制用例再次通过。IDEA 构建与关键文件错误检查通过，MCP 确认 EQP_TYPED_LOB_* schema 遗留为 0，探测源文件及本轮诊断日志已清理。更多关系包装/分片组合仍属于 G4.6，不将基础查询限制通过视作全部组合完结。说明见 [HANA LOB DSL](hana-lob-dsl.md)。

## 下一步

1. 检查 HANA LOB 提交的 CI，继续 HANA JSON 文本视图及三库原生类型目录。
2. 推进三库原生类型目录与 HANA 受限类型视图，保留通用标量表达式。
3. 继续关闭 HANA 历史历法、无显式列插入、distinct 聚合及完整查询源等已有问题。
4. 按依赖完成窗口、全文、HANA 层级/计算视图、干净消费者和完整成熟度验收。
