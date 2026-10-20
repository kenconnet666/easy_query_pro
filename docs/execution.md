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

## HANA JSON 文本与物理存储

提交 `68a34aacdd3a8935832723335bf93ba20e3ea9fd` 的 [CI 39382829009](https://github.com/kenconnet666/easy_query_pro/actions/runs/39382829009) 已通过全部五个强制任务，包含 LOB 返回类型、查询限制、byte[] 标量及既有回归。

HanaJsonTextExpression 复用全部共同 JSON 读取，只声明 NCLOB 或指定容量的 NVARCHAR，不实现不存在的 JSONB/原生修改。参数描述增加容量匹配和同值描述相等性，NativeJsonType 继续保留物理 SQL 名称；既有 MySQL/PG 语义不变。

JsonPathAccess 的导航前置检查从 HANA 构造函数分离，保留 ProDsl 在构造导航时的显式检查；完整 asText 只执行物理转换，不因没有部署 JSON 路径函数而失败。辅助函数部署继续由应用显式完成。NCLOB 的返回标记、排序/分组限制与新 LOB 设施复用。

本地 26 个离线/消费者编译检查、4 个 HANA 实库用例通过，包含共同路径回归、6004 单元 Unicode 值、完整文档、NVARCHAR 容量、业务 record、Draft/单列/克隆、JSON null/SQL NULL 和坏文本导航。消费者检查现在为 19 个正例、46 个负例。IDEA 构建与关键文件错误检查通过，MCP 确认 EQP_JSON_TEXT_* schema 遗留为 0；本轮编译/原生日志记录后清理。说明见 [HANA JSON 文本](hana-json-text.md)，更多类型目录与 G4.6/G6 组合继续实施。

## 默认行语法补充调查

以三个独立 schema 检查了当前 HANA revision 的 DEFAULT VALUES、DEFAULT 表达式、空 VALUES 元组、空列列表及 NULL identity 行为，均未找到可用替代路径，未重放已知导致崩溃的语句。SAP 官方 SQLAlchemy 方言的能力声明与此限制一致；Docker Hub 的官方 Express 标签也没有更新到比当前 88 更高的版本。

MCP 确认 EQP_DEFAULT_VALUES/EQP_EMPTY_TUPLE/EQP_NULL_ID 本轮 schema 遗留为 0；三份探测源文件清理。未更改生产翻译、数据库表结构或必交付范围。该项仍记录于 [HANA 写入说明](hana-writes.md)，继续执行独立的类型目录与组合任务。

## 三库原生数值与 HANA 指数绑定

提交 `f8783e42f6fe826805f2d4c666eba333e4cd0ff5` 的 [CI 39385043844](https://github.com/kenconnet666/easy_query_pro/actions/runs/39385043844) 已通过全部五个强制任务。独立整理提交 `d0a956a` 提取共同精确 decimal 输入校验，17 个既有离线测试和 IDEA 构建通过。

新增 MySQL 65 位 decimal、PG 指定/未指定精度 numeric、HANA 38 位固定 decimal、SMALLDECIMAL/浮点 DECIMAL 和 Short 载体的 TINYINT。所有入口仍返回既有 NumberTypeExpression，错库在构造时拒绝；新建类型描述未引入平行数字运算体系。

独立 JDBC 探测复现 ngdbc 2.30.11 对负 scale 的精度错误，以及 1E-6177 被错误编码为负的大指数值。HANA BigDecimal handler 对这些指数边界使用绑定文本，普通值沿用 setBigDecimal，并保留默认 handler 类别名。原生表验证覆盖最大/最小非零值、38 位固定小数、batch、完整实体、Draft、NULL、clone；typed value 拒绝损失，raw entity 保留服务端舍入/下溢。

本地首轮 39 个离线/消费者检查、7 个三库实库用例通过；补充 HANA raw entity/回滚/失败写入后，7 个离线检查和 6 个 HANA 实库用例通过，包含原有转换器回归。消费者编译现在为 20 个正例、50 个负例。IDEA 构建通过，MCP 确认三库本轮表/schema 遗留均为 0。实现及边界见 [原生数值目录](native-numeric.md)。

## HANA 字符目录与 Code First 复用

提交 `eb254541e96ca5ed933936c2cba3ace7f148f411` 的 [CI 39390670439](https://github.com/kenconnet666/easy_query_pro/actions/runs/39390670439) 已通过全部五个强制任务，包含新的数值目录和 HANA 指数修复。

HanaTypes 增加 ASCII VARCHAR、Unicode NVARCHAR、ALPHANUM 和仅用于存储的 SHORTTEXT 描述。前面三项复用通用字符串表达式。ALPHANUM 的纯数字补零通过 nativeCast 显式请求，typed value 只接受无表示变化的内容；SHORTTEXT 不暴露不存在的 CAST。普通字符串查询与业务实体继续沿用上游 API。

真实 JDBC 探测确认目录保留 SHORTTEXT、浮点 DECIMAL scale 为 NULL，现有 HanaCatalog/HanaColumnType 已能正确处理，因此没有添加平行元数据逻辑。新增 Code First 用例同时覆盖八种原生类型、再次生成空计划和 SHORTTEXT 自动全文索引保留。

本地 18 个离线/消费者编译检查和 3 个实库用例通过；补上 ALPHANUM UTF-16 满容量与自动索引断言后，2 个离线检查、3 个实库用例再次通过。消费者编译现为 21 个正例、53 个负例。IDEA 构建通过，MCP 确认本轮表/schema 遗留为 0。说明见 [HANA 字符目录](hana-characters.md)，Cloud 与本地部署的 ALPHANUM 能力明确区分。

## 共同逐行表达式与三库分片投影

提交 `7e14da6b6d0835f7bc5d6c2f877769c6c981acb6` 的 [CI 39392502617](https://github.com/kenconnet666/easy_query_pro/actions/runs/39392502617) 已通过全部五个强制任务，包含字符类型和 Code First 幂等检查。

用真实分表补入 G4.6 验证后，确认需要区分可下推的逐行表达式与聚合后转换。ExpressionScope 现在显式传播已知逐行分类；普通列/绑定值/共同转换可组合，聚合与未知包装保持拒绝。UNKNOWN 本身不作为安全依据。

实库同时复现了 3.2.17 Draft 的普通 columnFunc 被当成聚合列，进而触发 UNKNOWN 合并失败。最终修复收拢在公开的投影工厂/selector SPI，保留上游别名、列 metadata、克隆和 visitor；没有新建 AST 或合并器。后续补充验证包含仍公开但已 deprecated 的基本类型/DTO/Map 别名选择，其测试调用单独保留以防兼容回退。

最终本地 47 个离线检查、26 个实库用例通过，覆盖三库双分表、分页/count/clone、JSON/NULLIF/NULL-safe、已有 SUM 合并，以及 HANA/JSON/原生数字的回归。IDEA 构建通过；三库 MCP 确认本轮表/schema 遗留为 0。边界见 [分片说明](dsl-sharding.md)，该记录不把多 DataSource 和全部原生类型分片标为完成。

## 原生视图的逐行组合与 LOB 类型传播

提交 `3408b9fbaf21dda405d1307c46d6ed1361b407f3` 的 [CI 39397728470](https://github.com/kenconnet666/easy_query_pro/actions/runs/39397728470) 已通过全部五个强制任务。

ExpressionScope 新增单输入/双输入的标量组合辅助，操作数仅构造一次再传播分类；MappedValueExpression 保留原列读取契约并沿用已知分类。MySQL JSON/Duration、PG JSONB/数组/interval、HANA JSON 文本与 LOB 的现有操作接入这一基础，不增加新的公开运算方法。

原作用域包装是没有 visitor 信息的 SQL lambda，无法把 LOB 返回标记继续传给外层。改为公开 TableSQLSegment 的透明包装，HANA 返回类型检查沿 visitor 穿过包装，同时在具体 NativeProjection 的返回类型处停止；标量转换的 LOB 参数不会误触发结果限制。真正的 LOB DISTINCT/排序限制继续保留。

实库先发现数组测试没有投影排序 id，按上游契约修正 fixture，没有绕过排序检查。最终 15 个定向离线检查和 36 个实库用例通过，包含三库双分表、所有上述原生视图的代表性组合、克隆、原有 JSON/数组/时长/interval 映射回归；此前透明 metadata 变更还通过了 28 个离线和 18 个实库用例。IDEA 构建通过，三库 MCP 确认本轮分表/schema 遗留为 0。完整多数据源和派生关系仍继续执行。

## 分片测试整理与两个独立 DataSource

提交 `61616a8502dd3006941de59f13ee1192cf2f4ac8` 的 [CI 39401099817](https://github.com/kenconnet666/easy_query_pro/actions/runs/39401099817) 已通过全部五个强制任务。独立整理提交 `74d63ce` 将各方言原生断言迁回对应 IT，保留共同分片生命周期和断言；整理后 6 个离线检查、3 个实库用例和 IDEA 构建通过。

随后通过同一 Shard fixture 描述接入数据源路由。新增双 Hikari 池场景：PostgreSQL/HANA 使用不同 schema 的同名表，MySQL 使用独立表并混用客户端/服务端预处理。借连接计数验证分片键查询只访问目标数据源；新增定向更新回滚、重复键失败回滚及两池归还检查。测试继续使用现有实例，不新增 Docker 容器，也不把单路由事务验证称为分布式事务。

本地 6 个离线检查、6 个三库实库用例全部通过，包含原有单数据源双分表模式的回归。IDEA 构建通过；MCP 确认本轮多数据源 schema/表及分片 fixture 遗留为 0，所有新建池在作用域结束时关闭。运行过程中仅检查 CI 状态，没有阻塞等待它完成。

## 下一步

1. 核对本轮提交 CI，完善 DSL 泛型挂载和工厂扩展点，避免新增 final/private 创建链阻断定制。
2. 按统计报表蓝图补窗口规格与分组→窗口→外层筛选/分页等复杂组合。
3. 推进剩余类型目录，并继续关闭 HANA 历史历法、无显式列插入、distinct 聚合与派生 LOB 限制等已有问题。
4. 完成全文、HANA 层级/计算视图、干净消费者和完整成熟度验收。

## 派生关系读取器与报表方向审查

提交 `03f1b9ef1425b364648602cdacc8804b675deea2` 的 [CI 39403295050](https://github.com/kenconnet666/easy_query_pro/actions/runs/39403295050) 已通过全部五个强制任务。本轮三个子代理仅做只读研究/审查，所有编辑、复现和验证由主线程完成。

实库复现并修复了派生 JSON/数组 reader 丢失、CTE 隐式 SELECT * 空快照、列重排串用旧 reader。审查进一步指出 DTO 目标 handler 被覆盖、显式 valueConvert 被覆盖、上游 SUM 继承 JSON reader，三项分别通过 ClassCast、错误返回值与解析错误完成复现后修复。复审的无预先 clone 连续投影和同源显式 reader UNION 也已复现后修复。

读取注册在关系创建时复制，Draft 类型按上游赋值时序解析；CTE/UNION 按来源和输出位置保留映射。普通 DTO 保留目标契约，显式转换优先，标量/聚合不继承整列 converter。使用公开 ExpressionBuilderFactory、selector 和 ColumnReader SPI，没有私有反射或替换执行器。实现边界见 [投影映射说明](dsl-projections.md)。

本地 10 个离线检查与 36 个三库实库用例通过；补充 33 个离线/消费者编译检查与 9 个 HANA 转换及三库单/双 DataSource 回归也通过。消费者检查本轮耗时约 85 秒，后续完整重跑交给 CI，局部改动仅运行对应消费者用例。IDEA 构建通过。IDEA 数据库 MCP 核实本轮 JSON、数组、interval 和分片 fixture 遗留为 0；持久开发实例未改动。

蓝图增补 A01–A04 与 G5.5–G5.8，明确开窗、完整报表链、多级汇总、基础统计和时间序列的归属与验收。既有条件聚合、排名、偏移和普通日期截断继续复用；CUBE、动态 PIVOT、大而全统计目录不自动扩入。另修正文档中工程仍为空包、JsonDocument/DateBucket/cast 等旧状态或示意命名。新增计划不代表已完成，当前候选提交仍需自己的完整 CI。

## 泛型挂载与公开 SPI 基础

提交 `92af9ff10b1a0ff0e8667b3ef4b60c1c3be3d468` 的 [CI 39412177500](https://github.com/kenconnet666/easy_query_pro/actions/runs/39412177500) 已通过五个强制任务。用户要求减少封闭继承边界后，三库 facade 改为继承 ProDsl，共用 ExpressionScope；新增具体 DSL 和表达式的泛型工厂挂载。公共基类/默认工厂迁入 spi 包，selector/关系创建和 CTE clone 经过 protected 钩子。HANA 默认 SQLFunc/ExpressionFactory/SQLSegmentFactory 可继承。

干净编译后消费者暴露 Java 17 隐式 lambda 与重复依赖泛型上界的冲突。最小 javac 原型确认原因后，将 E 与 T 的严格关系放在挂载方法上，工厂只约束结果为列表达式；构造器引用、lambda、精确返回类型及错值类型负例同时通过。此次未发布快照的旧内部包不提供二进制桥接，文档要求统一升级模块和重新编译扩展。

复审还发现 primitive 装箱归一化会掩盖读取器的 SQL NULL→0 差异，已恢复 primitive 与包装类型的兼容性区分，并以 PostgreSQL NULL 分支证明 UNION 在执行前拒绝错误组合。

本地 17 个既有 DECIMAL 检查通过；最终聚焦验证为 6 个离线/消费者检查、27 个三库实库用例通过。完整消费者套件交 CI；本轮只重跑新增的 2 个正例和 2 个负例。统计子代理另通过少量只读 SELECT 核查三库方差/标准差精度，结果写入统计报表蓝图，未据此宣称 A03 DSL 已实现。G4.7 的 JSON/interval 等同族 SELF 重建仍继续推进。

IDEA 首次构建等待超时，随后重试成功且无构建问题；新 JAR 仅包含新的 spi 路径，没有旧内部扩展类残留。三库 MCP 确认本轮 JSON fixture 遗留为 0；已删除自有泛型 javac 探测文件，未清理持久开发库。

## 同族表达式 SELF 与查询分支时机

提交 `f19bef7ec633fb4dfeb3c364a0a6dd8b089913a1` 的 [CI 39416170802](https://github.com/kenconnet666/easy_query_pro/actions/runs/39416170802) 已完成成功。MySQL JSON 和 PG interval 从私有实现中抽出公开泛型基类；默认实现只负责构造和 recreate。同族操作返回具体 S，标量结果保持原类型。扩展作者不必复制 SQL、绑定、校验或读取映射。

新增两个消费者扩展同时用于编译和实库验证。9 个离线/消费者检查通过，MySQL JSON 与两库分片回归通过。PG 新 CTE 测试最初在终端读取后再次 select，SQL 证实旧列与新列被追加在一起；对照 3.2.17 compensateSelect/clone 契约修正为先 clone 再终端执行，5 个 PG interval 用例随后全部通过。合计 13 个实库用例分批验证，未修改 reader 算法去隐藏多余列。

一次尝试直接执行 reactor Failsafe 目标因父项目强制 failIfNoTests 在测试开始前退出，该尝试不计为验证。最终使用正常 Maven verify 路径完成上述 PG 回归。查询复用说明同步记录 toSQL/single/list/page 的构建状态影响，下一步窗口报表按此时机建立独立分支。

IDEA 构建通过；MySQL/PG MCP 确认本轮 JSON、interval 与分片 fixture 遗留为 0。子代理仅复审并建议补全 recreate 契约注释，未写入代码；对应注释已由主线程补入。
## 共同窗口规格与报表计算层

提交 `fc72da2f1ec01b3d93726f4d70f24f1b7cda19cd` 的 [CI 39419792747](https://github.com/kenconnet666/easy_query_pro/actions/runs/39419792747) 已通过五个强制任务。本轮新增不可变 SELF 窗口规格、共同 ROWS、全局窗口，以及对既有聚合/NextSelector 的薄组合；不复制排名/偏移函数，也不改写最终 SQL。

三库报表包含分组后的累计、移动均值、全局金额、首末/Nth 值、空 frame、外层 EQ/IN、count/page 与 DISTINCT→CTE。故障回归先复现了 SMART_PREDICATE 将累计 35 错算为 5、primitive NULL 读成 0/false，以及派生业务 record 在 selectColumn 被当成实体构造。修复分别位于公开 predicate provider、投影 reader 与 ResultColumnMetadata SPI；别名/原生参数中的独立子查询保留各自计算层，HANA LOB visitor 继续委托原实现。

泛型规格和结果工厂经 JDK 17 消费者正负例验证。对象族与数值/文本族的默认重载分别保持原映射和具体表达式族；业务 JSON、interval 经过 CTE 后的单列窗口仍返回业务值。扩展裸聚合使用可信标记，已知 DISTINCT、聚合外包装、同层嵌套窗口提前拒绝。跨分片窗口及其 CAST/NULLIF 包装不套用普通逐行合并。

最终定向验证为 31 个离线/消费者检查与 12 个实库用例通过；此外 37 个相关 JSON、数组、时长及原有映射回归已经通过。执行使用 Maven Wrapper、指定 JBR 17 与三库 profile；完整消费者和全量数据库测试交当前提交的 CI，旧 SHA 的成功不覆盖本轮。IDEA 构建成功，MCP 核实窗口及相关 fixture 的表/schema 遗留为 0，持久开发实例保留。

只读方言核查另确认：共同 ROWS 的位置顺序规则需兼容 HANA；MySQL/PG 原生 RANGE 要保留零边界类别。MySQL 8.4.11 常量单行查询的空窗口存在数据库预执行优化差异，原生 COUNT 同样返回 1；以非恒定数据验收框架，没有加入 SQL 补偿。上游部分已有 offset 窗口缺少 metadata，使用显式 CTE 边界，不通过扫描 SQL 伪造完整检测。详见 [窗口说明](dsl-windows.md)。

G5.1 的 MySQL/PG RANGE、A03 四个近似统计入口与 A02 分组汇总继续实施。子代理已经核查实际增量与公开 SPI；ROLLUP 的空输入差异、date_bin 的 origin/微秒语义和 JDBC 时间绑定风险进入后续验证清单，尚未标为实现完成。

## 近似统计与报表 fixture 复用

共同窗口提交之后的 `7000c56cb2bafdcef6cf5d1cf4d6e51c73391222` 已通过 [CI 39433617617](https://github.com/kenconnet666/easy_query_pro/actions/runs/39433617617) 的五个强制任务。三库窗口 IT 收纳为 ReportingIT，销售 fixture 的创建与关闭单独整理，随后加入统计契约；没有新建数据库实例或额外持久对象。

A03 新增总体/样本方差及标准差四个入口，复用 NumberFilterTypeExpression、CASE 筛选、既有窗口和 Double handler。HANA/PG 复用原 CAST；MySQL 在统计内部显式 CAST AS DOUBLE，避免上游 Double CAST 实际变成 DECIMAL。三库验证真实 NULL、空集、单样本、微小小数、2^53 精度边界、条件样本、HAVING、分组 count/page、累计及全局窗口，JDBC 元数据均为 DOUBLE/java.lang.Double。

只读审查指出统计自身的 UNKNOWN 枚举和 FILTER 的 CASE 可以掩盖非法嵌套。新增负例在三库离线客户端复现后，将原输入校验放到 FILTER 包装之前，并识别明确声明的 Pro 聚合；不扫描最终 SQL 或重写上游未知包装。普通计算样本继续交原 SQL 表达式链，跨分片拒绝保留。

32 个离线/消费者检查和 9 个三库报表用例通过；补充 HAVING 后，12 个统计离线检查和 3 个统计 IT 再次通过。IDEA 构建成功，三库本轮表/schema 遗留为 0，失败探测日志在记录证据后清理。当前统计提交的完整 CI 异步执行，继续推进原生 RANGE，结果见 [统计说明](dsl-statistics.md)。

## 原生 RANGE 与参数化距离

统计提交 `5ffebe2c771e80540dcca910b766fb2113a44fd9` 已通过 [CI 39437039654](https://github.com/kenconnet666/easy_query_pro/actions/runs/39437039654) 的五个强制任务。MySQL/PG 新增原生窗口规格，共用 RangeBoundary/RangeFrame 语法与 WindowSpec 状态，各自负责数值或时间偏移的类型和参数形式。

保留零偏移类别，允许同类别倒序形成空 frame；PG 整数 offset 使用 BIGINT，精确小数使用 NUMERIC；MySQL 采用原生参数标记或 INTERVAL 参数加受控单位。时间范围区分自然月、自然日和固定微秒。有限 offset 的键与参数保持明确范围，不自动接受浮点、时区时间或混合正负 interval。默认工厂的泛型挂载放宽到 WindowSpec 子类，SELF 仍由规格自身约束，消费者可以继续保留具体扩展方法。

27 个离线/消费者检查与 4 个实库参数模式用例通过。实库覆盖重复值、精确小数、NULL 键、零/空 frame、升降序、日期间隙、闰月及微秒、CTE/count/page 和 SQL/参数克隆。MySQL 两种 prepare 均禁用不支持语句的静默降级，PG 同时验证普通和强制准备；这些用例仅使用常量关系，没有创建数据库对象。补充禁止的无界组合与负 interval 后，7 个范围校验用例再次通过，IDEA 构建通过。

用户要求完善中文注释后，先在新的 RANGE 值对象、方言规格和共同语法层补齐类型、扩展与错误契约；已有映射、窗口、统计与 HANA 关键生命周期的注释继续单独整理。完整 RANGE CI 异步提交，说明见 [窗口文档](dsl-windows.md)。

## 中文注释与维护契约补全

RANGE 提交 `d3285b2d57a8b910d5e43cd40f22ebc517b1593b` 已通过 [CI 39441675730](https://github.com/kenconnet666/easy_query_pro/actions/runs/39441675730) 的五个强制任务。按用户要求，三个只读子代理分别审查共享映射、原生类型和 HANA 生命周期；主线程在 43 个实现/契约文件中补充或改写中文 Javadoc 与关键原因说明。

回补覆盖泛型工厂的 Java 17 上界选择、SELF 重建、两阶段 reader 快照、Object 标量载体、可空窗口、别名/子查询 visitor、统计筛选与精度，以及 JSON/数组/interval/Duration 的值域与 NULL 区别。HANA 部分说明会话取键时序、连接所有权、DDL 模式恢复、权限过滤、LOB 返回类型和无时区日期读取。报表测试也补上固定期望值与故障分支的设计意图。

使用保留字符串/字符字面量的词法核对，确认 43 个 Java 文件注释外 token 未改变；IDEA 格式与构建通过。该批只整理注释，未扩大公共契约或另跑全量数据库测试。为避免将旧说明机械翻译成错误结论，另外通过只读 MySQL 查询复核 TIME_TO_SEC 的整数秒行为；没有创建数据库对象或修改数据。

## 结构化分组汇总与查询边界

中文注释提交 `57f7e9da325937f59c82f711aa2fb9cfc08df33a` 已通过 [CI 39445505407](https://github.com/kenconnet666/easy_query_pro/actions/runs/39445505407)。随后复审补全默认 frame、自定义 frame 不可变契约、四项近似统计说明、Java/SQL 类型区分及四个核心 SPI 的安装说明，单独整理为 `d3f4553`。所有研究代理只读，主线程完成实现与验证。

A02 新增共同 ProGroups.rollup/mask、PG/HANA groupingSets 和结构化 GroupingSet，继续返回原 EntityQueryable；原分组代理及多表来源泛型完整保留。输入先 clone，再替换实际 GROUP 段并立即明确投影，既有条件与参数仍由原 segment 构造。PG 共同 ROLLUP 的空输入经 COUNT(*) HAVING 对齐，原生集合保留空输入差异，已有 OR HAVING 保留括号。

三库回归先后复现了小计 primitive NULL 被读成 0，以及 SMART 将外层地区筛选提前、使 3 行结果变为 4 行。修复位于共享语义 visitor、投影 reader 与 predicate provider；新 Draft 的类型槽、自动 reader 和关系快照一起保留可空读取，DTO 继续按目标类型处理。上游 group 容器需要显式遍历，标量子查询仍停止当前层语义检测，没有伪造空分组或修改最终 SQL。

JOIN 原型还确认 EasyQuery 3.2.17 的两个同源 clone 共享 Proxy，ON 退化为右侧列自比较，总额 60 变成 180。该行为在进入 ProGroups 前已经发生；测试改为两个独立来源，并把 clone 自连接边界写入投影文档，未在汇总层掩盖错误 ON。

41 个离线/泛型消费者检查与 12 个三库实库报表用例通过，覆盖普通分支、真实 NULL、小计、DTO、参数键预投影、JOIN、SMART、CTE/count/page 和既有窗口/统计。补充 mask 数量及绑定 Draft 所有权负例后，12 个分组离线检查再次通过。IDEA 格式、构建通过，MCP 确认本轮报表 fixture 遗留为零；新增分组用例仅使用常量关系，没有创建新实例或数据库对象。完整候选 CI 异步提交，下一步继续 HANA 参数化来源和时间序列增强，不以 A02 阶段代替完整目标验收。

## HANA 标量计算视图来源

分组提交 `9b9252f44a629b6048cff18174375cf36e1dcb32` 已通过 [CI 39453615339](https://github.com/kenconnet666/easy_query_pro/actions/runs/39453615339)。H11 本轮新增 HanaInput 与 HanaCalculationView，通过原 queryable(sql, class, parameters) 返回 EntityQueryable，不引入执行器或额外数据库往返。定义固定对象名、输入顺序和输出 Proxy 工厂，每次调用创建新的来源代理，输入值只走 JDBC 参数。

真实 HDI fixture 从直接 JDBC 扩展到完整查询链，显式映射 ID/NAME/AMOUNT 大写列。已验证单引号/中文、注入文本不扩大结果、BigDecimal、外层参数顺序、Draft、CTE、聚合、独立来源 JOIN 和 count/page。默认值 XML 参照固定 SAP SHINE 模型的 defaultRange，省略参数命中默认值而显式 NULL 返回空结果；缺参、重复赋值、同名异声明、错类型和重复使用已绑定 Proxy 在离线边界拒绝。

复审新增的 PositiveInput 负例复现 nullable 重建丢失子类校验，现改为可空声明对非 NULL 委托原验证。补充原 client 事务内写入可读、回滚后消失的实库证据。最终 7 个离线/消费者检查与 1 个真实 HDI 生命周期用例通过；IDEA 构建通过，MCP 确认本次 EQP_HDI schema 遗留为零。完整 CI 异步提交。多值模型协议与标量输入表函数仍是 H11 的后续必需项；子代理已查得官方 XML 与单个 JDBC 字符串的列表协议，下一步实库验证，不把普通 CSV 或空字符串元素当作空列表。

## HANA 多值计算视图输入

标量来源提交 `3b2fb594e70d8515f7774e315207db7aec6bc5f2` 已通过 [CI 39456339767](https://github.com/kenconnet666/easy_query_pro/actions/runs/39456339767) 的五个强制任务，交接前重新读取确认 tested SHA 一致。新增文本/数值列表声明，逐元素编码后只绑定一个 JDBC 字符串；内部逗号、单引号、中文、空字符串和注入样式文本仍是模型值。HanaInput 公开受控的 toJdbcValue 扩展点，nullable 继续保留自定义验证与编码，显式 NULL 跳过编码。

真实 HDI 新增 NVARCHAR/INTEGER 多值计算视图，验证精确匹配、重复元素、一个参数的列表协议、克隆、CTE 与 count/page。当前 HANA 2.00.088 对整个 JDBC NULL 列表展开为无效 IN ()，原生错误为 2048/34023，因此列表声明明确拒绝 nullable、空列表和 NULL 元素；单个空字符串元素有独立实库正例。数值模型目前只验证 INTEGER，BigDecimal 规范编码的离线结果不替代其他 SQL 数值模型的精度验收。

本地正常 verify 路径通过 8 个离线/消费者检查与 1 个真实 HDI 生命周期用例。随后补 nullable 编码保留断言，7 个 HanaSourceDslTest 通过。只读复审无阻塞问题，收尾增加编码调用次数、clone 不重编码、显式 NULL 不调用编码器及列表编码快照检查；使用指定 JBR 17 执行 `mvnw.cmd --batch-mode test -Phana -Dtest=HanaSourceDslTest -Dsurefire.failIfNoSpecifiedTests=false`，7 个检查再次通过。IDEA 构建已通过，MCP 确认 EQP_HDI schema 遗留为零；本轮三份诊断日志记录后清理，标准测试报告与持久开发实例保留。

2026-10-21 用户调整分工，Java 实现交由指定代理继续，主线程串行提交、推送并推进独立的 TypeScript 方向。当前多值批次的完整 CI 留给新提交执行；下一步接入标量输入表函数，共享声明校验和 fresh Proxy 生命周期，保留计算视图 API，不另建执行器。

## HANA 标量输入表函数

多值批次 `5f8af9e083e11ff8260a1545aa39b097ff810011` 已通过 [CI 39459057577](https://github.com/kenconnet666/easy_query_pro/actions/runs/39459057577)，提交交接前核对运行 SHA 与结论一致。H11 新增 HanaTableFunction，与原 HanaCalculationView 通过包内 HanaQuerySource 组合共享声明/赋值校验、编码、方言检查及 fresh Proxy 生命周期。两个公开入口保留各自 protected 构造器和清晰语义；表函数统一使用命名实参，零实参仍有调用括号，不引入额外执行器或目录查询。

在现有 HDI 生命周期内加入 EQP_FUNCTION.hdbfunction，真实验证 HANA 2.00.088 支持 `"P_NAME" => ?`，省略中间参数仍正确使用 DEFAULT，显式 NULL 不等于缺省。文本、空字符串、注入样式文本和 BigDecimal 均走 JDBC。外层参数顺序、Draft 聚合、独立来源 JOIN、CTE/count/page 和原 client 事务回滚全部通过；计算视图单值/多值回归同轮执行。函数采用 SQL SECURITY INVOKER，测试容器额外授予 EXECUTE，未扩大已有持久 schema 权限。

使用指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Phana -Dtest=HanaSourceDslTest,DslCompilationTest#hanaSourceInputsAndOutputProxyStayTyped -Dit.test=HanaHdiIT -Dsurefire.failIfNoSpecifiedTests=false`，12 个离线/消费者检查和 1 个真实 HDI 生命周期用例通过。只读复审未发现阻塞缺陷，补充说明列表声明用于函数时仍为协议字符串，不代表 SQL 数组或表参数。IDEA 格式与构建成功；MCP 查询 SYS.SCHEMAS 确认 EQP_HDI schema 遗留为零，诊断日志记录后清理。表函数完整 CI 将随本批提交异步执行，完整 H11/G6 的非管理员、更多模型精度及干净产物消费仍继续验收。

## HANA 邻接树生成器与外层渲染上下文

表函数提交 `166db7d0639eb2d4004407afbd41987d90d272bd` 已通过 [CI 39460618488](https://github.com/kenconnet666/easy_query_pro/actions/runs/39460618488)。H12 的 HanaHierarchy/HanaHierarchySpec 以原查询快照为来源，通过 HANA HIERARCHY 生成树与森林；键和根保持相同泛型，兄弟顺序、最大深度、多父节点、循环与孤儿策略显式表达。定义创建时用公开 native subQuery 接入，完整 SQLParameter、列 converter、CTE 和查询上下文继续交上游处理，不提取 SQL/参数值后重建。

两层来源规范化隔离 HANA START 与 SIBLING 对列别名的不同规则，保留原业务列并补 NODE_ID/PARENT_ID。结果以 fresh DTO Proxy 进入原 EntityQueryable，新 QUERY context 不继承来源临时 reader。实库验证多根、根集合、深度、稳定兄弟顺序、循环闭合、孤儿和重复节点错误、外层 SMART 展示筛选、CTE/count/page、来源变更快照，以及 CodecRecord 业务键的 ValueConverter/String handler。SQL converter 路径复用公开 createColumnCompareValue2Segment，本轮业务键实测不冒称非平凡根键 SQL converter 的完整覆盖。

只读复审指出 clone 后内外 ExpressionContext 分开，外层追加分片时内层检查会失效；新增负例首先复现未抛异常，再由 HanaQuerySQLContext 将本次外层分片标记沿同一 ToSQLContext 渲染链传递。该具体包装全部委托参数、别名、计数器与 SQLRewriteUnit；不扫描 SQL，也不加新执行器。修复后 clone、真实分片表 JOIN、CTE 后 JOIN 均拒绝。随后复现 APT 对不同业务 record 都使用 Object.class 载体导致 raw 错键漏检，改为实体映射中的实际值类型；编译约束与运行时边界分别保留。

验证使用指定 JBR 17 与正常 Maven verify 流程。先通过 4 个离线检查和 3 个生成器实库用例；补充边界过程中修正测试装配问题：复用同一离线 client 注册分片初始化器、ColumnReader 使用零基索引、注册既有 CentsConverter、跳过包装 SQLException 的零错误码后读取真实 HANA 诊断。分批已通过来源 11 个离线检查、1 个消费者编译用例、生成器 5 个实库用例、原查询组合 5 个用例及 HDI 生命周期 1 个用例。最后以 `mvnw.cmd --batch-mode verify -Phana -Dtest=HanaHierarchyDslTest -Dit.test=HanaHierarchyIT#businessKeyRootsKeepTheSourceConverterAndJdbcHandler -Dsurefire.failIfNoSpecifiedTests=false` 通过 6 个层级离线检查和业务键实库回归；本批唯一用例合计为 18 个离线/消费者检查与 11 个实库用例。

IDEA 格式与构建通过，MCP 确认本轮 HDI schema 遗留为零。七份自有诊断日志在记录红绿证据后清理，标准测试报告保留；持久三库实例未修改。只读复审确认 root context 委托与公开 converter 路径无新增阻塞问题，完整 CI 随新提交异步执行。

生成器文档见 [HANA 层级来源](hana-hierarchy.md)。祖先/后代导航、距离与组织事实汇总仍继续实施，当前生成器不能替代完整 H12/G5.3/G5.8 验收。除现有 HDI 回归自有容器生命周期外，新增层级测试只使用常量关系，不创建持久对象或数据库实例。

## HANA 祖先/后代导航与组织事实汇总

生成器提交 `1ba0738dcccb114f01f3a6bb377e49afe3021fd3` 对应同一 push 有两个运行：`39465419959` 已取消；同 SHA 的 [CI 39465420218](https://github.com/kenconnet666/easy_query_pro/actions/runs/39465420218) 成功。已读取后一个运行的 SHA 与结论，不将取消的运行写成成功，也未阻塞等待 CI。

HanaHierarchy 增加不可变 descendantsOf/ancestorsOf 和有符号 distanceBetween，键类型、绑定、根定义与 clone 继续保留。两方向包含自身，重叠子树按 START_RANK 分别输出，重复输入键采用 IN 集合语义。距离为 JDBC 参数的闭区间，保留原生负数和反向空区间；不取绝对值或改变原层级 TREE_SIZE。导航 SOURCE 直接组合受控 HIERARCHY 函数节点，绕开 HANA 不接受任意普通子查询的语法边界，未解析或改写最终 SQL 文本。

组织报表选择复用既有聚合：事实先按节点预聚合，再 LEFT JOIN 后代关系，最后按 START_RANK 汇总。真实 fixture 含一节点多条事实、重叠起点、空叶、NULL 金额、完全无事实新根及不属于树的事实。断言事实金额、节点自身金额、节点数、事实记录数分别正确；无事实记录数显式使用既有 nullOrDefault(0L)，金额 SUM 保留 NULL。该组合没有另建 MEASURES AST、SUM/COUNT 包装或报表执行器。

最初消费者编译暴露 Draft 的 Long 槽是 SQLAnyTypeColumn，按已有 API 显式 asLong 后聚合。最终使用指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Phana -Dtest=HanaHierarchyDslTest,DslCompilationTest#hanaHierarchyKeysRootsAndOutputRemainTyped -Dit.test=HanaHierarchyIT -Dsurefire.failIfNoSpecifiedTests=false`，8 个离线/消费者检查与 7 个实库用例通过。覆盖起点/距离参数顺序、类型负例、业务键、空来源、缺失起点、重复及重叠、CTE/count/page、SMART 展示和既有生成器回归。只读复审补充无事实根与集合语义说明后无阻塞问题；IDEA 格式与构建通过。

本批仅使用常量关系，没有创建持久数据库对象；两份自有诊断日志记录后清理，标准报告保留。当前导航提交的完整 CI 将异步执行；接下来继续 A04 日历补桶/PG date_bin、全文和完整消费者/权限验收。

## PostgreSQL 无时区时间与分片读取基础

导航提交 `549a37bafc7cf49daffe586d8f143d69a09f2add` 已通过 [CI 39468072110](https://github.com/kenconnet666/easy_query_pro/actions/runs/39468072110)。准备 A04 时先以纽约 JVM 时区、prepareThreshold 0/-1 两模式复现：LocalDateTime `2024-03-10T02:30:00.123456` 绑定到数据库以及独立 server literal 读取都会变成 03:30。问题发生于上游 Timestamp.valueOf/getTimestamp 路径，不能在 date_bin 内部拼字符串绕过。

PG 增强默认 handler 改用 JDBC 4.2 LocalDateTime，专用 manager 保留原 UUID 与显式 LocalDateTimeTypeHandler 类名别名，应用后注册的自定义处理器和子类仍优先。分片排序/分组会先缓存无类型 getObject，故在进入 merger 之前将真正 timestamp 的值保留为 LocalDateTime；同时检查真实 PG 类型名，timestamptz 不被误判。SQL 执行仍调用原 DefaultJdbcSQLExecutor，未复制查询、事务或绑定实现。

复审进一步复现普通查询自定义 handler 将 getObject 强转 Timestamp 时的 ClassCastException。修复将规范化包装限于分片结果，普通查询保留原 pgJDBC 无类型值；默认 typed handler 仍能修正普通读取。再核对公开 StreamResultSet 已有 typed getter 后，删除不必要的 Statement 捕获与 ExecutorContext 代理，直接委托原对象，减少生命周期状态。

使用指定 JBR 17，最初 8 个离线检查与 14 个实库用例通过；新增自定义兼容负例修复后，以 `mvnw.cmd --batch-mode verify -Ppostgres -Dtest=PgTemporalMappingTest -Dit.test=PostgresTemporalBindingIT -Dsurefire.failIfNoSpecifiedTests=false` 再通过 2 个离线与 8 个时间实库用例。本批唯一用例共 8 个离线、15 个实库，覆盖缺口/重叠/NULL、显式旧/自定义 handler、UUID、timestamptz、两分表排序/分组/去重，以及 interval 和单/双 DataSource 分片 DSL 回归。

IDEA 格式与构建通过，MCP 确认本轮 temporal/interval/sharding schema 遗留为零。四份自有诊断日志记录后清理，标准报告保留；JVM 时区均在 finally 恢复，持久开发库未修改。此批先交付时间基础，PG date_bin 与日历补桶随后实现；完整当前提交 CI 异步执行。安装与兼容边界见 [PG 时间映射](pgsql-temporal.md)。

## 固定微秒分桶与三库日历补月

提交前检查 PG 时间基础 `64951b46a5c768d4f85c0079a83fc63d0515a74f` 的 [CI 39472269506](https://github.com/kenconnet666/easy_query_pro/actions/runs/39472269506) 为 cancelled，未将它记成成功。继承该基础的 `afd877800d82f2cf424bce4e3289b18dcb0c4622` 在本批检查时 Java [CI 39473357054](https://github.com/kenconnet666/easy_query_pro/actions/runs/39473357054) 仍 in_progress；继续本地必要验收，没有阻塞等待。

PgSqlDsl.dateBin 只接受 LocalDateTime 来源、正且精确到微秒的 Duration 与有限 origin，返回原 DateTimeTypeExpression。步长复用 PgInterval 绑定，秒乘法与小数加法分别检查溢出；NULL 来源保留 NULL，origin 的 MIN/MAX 无穷哨兵和亚微秒值拒绝。固定桶不引入 Instant/时区自然日，也不重复已有月份/年截断。

三库共享 CalendarReportContract 用参数化日历来源 LEFT JOIN 月度事实，之后复用上游 LAG，再以 CTE 固定展示筛选边界。真实结果分别验证无事实二月、NULL 金额四月、完全空事实集、原始金额与显示零值，以及补齐后的上一月/count/page。保留“没有记录”和“有记录但金额为 NULL”的业务差别，没有新增报表执行器。

指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Phana,mysql,postgres -Dtest=PgDateBinDslTest,DslCompilationTest#postgresFixedTimeBinsRequireLocalTimestampsAndDurations -Dit.test=PostgresDateBinIT,PostgresReportingIT#calendarMonthsKeepEmptyBucketsBeforeLagAndPaging,MySqlReportingIT#calendarMonthsKeepEmptyBucketsBeforeLagAndPaging,HanaReportingIT#calendarMonthsKeepEmptyBucketsBeforeLagAndPaging -Dsurefire.failIfNoSpecifiedTests=false`，5 个离线/消费者检查与 7 个实库用例通过。PG prepareThreshold 0/-1 覆盖负向归桶、精确边界、2 微秒步长、NULL、纽约 JVM DST 字段及东京会话、CTE 分组与分页；三库补月各一用例包含非空与空事实两条完整查询链。

只读复审未发现必修问题，确认参数保持绑定、日期类型边界和补齐后窗口语义。IDEA 格式与构建通过。本批只使用少量常量关系，没有创建临时表/schema/容器；JVM 时区在 finally 恢复。自有 `date-bin-calendar-verification.log` 记录证据后清理，标准测试报告保留。文档见 [时间分桶与缺月报表](reporting-time-series.md)；当前候选提交完整 CI 仍由后续提交异步验收。

## 结果包装失败与二次清理异常

按主线程复审建议，新增离线 JDBC 故障注入：先令结果元数据读取失败，再令 PreparedStatement.close 抛 RuntimeException 或 Error。通过实际 PG/HANA executor→上游执行器→结果包装链复现四个用例丢失最初 SQLException；另两个同一异常实例的对照用例保持原异常。测试准备阶段补齐假连接的 getDataSourceName，以便原有 SQL 日志路径正常执行，未访问数据库。

两个适配器的失败清理现同时捕获 SQLException/RuntimeException/Error，并在不同实例时附加 suppressed；不改变上游执行和资源所有权，不重试关闭或重复关闭原始 ResultSet。上游自己记录而不向外传播的 checked 关闭异常仍沿原行为，本修复不声称改变上游所有资源异常契约。

指定 JBR 17 执行 `mvnw.cmd --batch-mode test -Dtest=QueryResultCleanupTest -Dsurefire.failIfNoSpecifiedTests=false`，六个故障注入用例通过，分别断言原异常身份、suppressed 及结果/Statement 各关闭一次。IDEA 格式与构建通过，未重复无关实库或完整 CI。前一提交 `d4bcee9bb1949584fdb0ec6d12f08aa14bcff8db` 的 [CI 39474709021](https://github.com/kenconnet666/easy_query_pro/actions/runs/39474709021) 在检查时仍 in_progress，未等待。两份本轮自有诊断日志记录后清理，标准报告保留。

## HANA 全文索引、原子评分来源与查询组合

开始本批时核实 `e9a10703c545ab884b97971d0cab76f6a5a5c943` 的 [CI 39475700796](https://github.com/kenconnet666/easy_query_pro/actions/runs/39475700796) 已 completed/success，覆盖此前 PG 时间基础、A04 与清理异常修复。当前全文批次仍需自己的 CI，不把这一基线成功当作新实现通过。

先通过 Context7 resolve/query SAP HANA Platform SQL Reference，再在专用 schema 中创建真实 COLUMN TABLE 与两个 SYNC FAST PREPROCESS ON 全文索引。原生 JDBC 证明搜索词及 FUZZY(?) Double 阈值均能绑定；EXACT、多列、拼写近似、NULL 与缺索引失败正确。未采用数字 SQL 字面量，也未用 LIKE 替代全文。

HanaSearchSpec 保存不可变 EXACT/FUZZY 条件和 AND 组合，限制最多一个多列 CONTAINS。HanaSearch 只接单物理表映射的普通 String 列，在同一原查询装配 CONTAINS 与 SCORE；只克隆 ClientQueryable，避免重绑定调用者 Proxy。输出使用 fresh DTO/QUERY context，并通过薄匿名来源接入原 EntityQueryable；SQL 参数、过滤器、事务和结果执行继续沿上游。公开 API 不提供能脱离搜索层的 SCORE 表达式。HanaSearchQueryBuilder 保留 clone/visitor 和外层分片标记，使后续 SMART、CTE、分页/count 保持正确层次。

离线负例首先捕获上游 CTE 的表 builder 并不标 anonymous，改用公开 CteTableAvailable 同步识别，覆盖 CTE clone。只读复审又指出原 WHERE 可能含根级 OR；用 ClientQueryable 的公开 or() 构造 `id=3 OR id=1`，实库复现搜索错误返回 `[1,3]`，其中 id=3 的 green tea 绕过搜索。修复复用已有 GroupOperations 的 PredicateSegment copy/reset 包组方式，只在快照中将完整原条件与 CONTAINS 相与，回归正确返回 `[1]`。

两个额外边界明确记录而未伪装修复：EasyQuery 3.2.17 对只有 asSchema/asTable 的右侧 queryable JOIN 会丢动态来源，测试通过实际 select 投影保留该来源；HANA 88 不支持 JOIN 两个各自 SCORE() 的子查询，负例检查真实 SQLException 错误码 7。两个评分来源的 UNION ALL、外层排序和 count 则实际通过；一个搜索内多个 CONTAINS 的综合评分也与原生一致。

最终指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Phana -Dtest=HanaSearchDslTest,DslCompilationTest#hanaSearchRequiresTextColumnsAndKeepsTheScoreType -Dit.test=HanaSearchIT -Dsurefire.failIfNoSpecifiedTests=false`，7 个离线/消费者检查及 4 个实库用例全部通过。覆盖错库、普通列/函数类型负例、参数顺序、阈值/Unicode 边界、快照与 fresh Proxy、晚分片拒绝，以及真实索引目录、EXACT/FUZZY 的原生分数对照、引号/中文/OR/短语、多列和 AND、SMART、普通 JOIN、UNION ALL、分页、临时 reader 隔离、同事务新增命中及回滚后消失。

IDEA 格式与最终构建通过；MCP 查询确认 `EQP_FULLTEXT_%` schema 遗留为零。四份本轮自有诊断日志记录后清理，标准报告保留，持久开发库不变。使用说明与不支持边界见 [HANA 全文查询](hana-fulltext.md)。MySQL/PG 全文和完整非管理员/发行消费者仍继续实施。

## MySQL 显式查询作用域基础

核实 HANA 全文提交 `086aadd8ecaf2c70fc3ac76faae3fa20c0e8deb4` 的 [CI 39479458044](https://github.com/kenconnet666/easy_query_pro/actions/runs/39479458044) 已成功。为后续 MySQL/PG 查询级来源复用，通用 DSL 新增 ProQuerySQLContext，仅沿原 ToSQLContext 委托全部参数、别名、SQLRewriteUnit 与根调用状态，并保存首次最外层的分片标记。嵌套 CTE/子查询不以自己的非分片状态覆盖它；HANA adapter 的现有实现保持独立 sql-core 依赖。

MySqlProExpressionFactory 继承上游 MySQLExpressionFactory，只覆写普通 SELECT 的创建，并继续继承上游 MySQLQuerySQLExpression 渲染；其他 DML、表、CTE、UNION 与 clone 路径不复制。安装方案经主线程复审调整为显式 `MySqlExtensions.configureQueryScope`，普通 configure 保留原先已注册的 ExpressionFactory。避免为了探测注册项而提前构建业务 Factory 或保存旧 ServiceProvider。应用后置自定义工厂可继承公开 MySqlProExpressionFactory。

初始方案完成 5 个离线与 9 个实库回归；改为显式安装后，指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Pmysql -Dtest=MySqlQueryContextTest,DslExtensionTest -Dit.test=MySqlQueryContextIT,MySqlConnectionIT,MySqlReportingIT,MySqlShardingDslIT -Dsurefire.failIfNoSpecifiedTests=false`，6 个离线与 10 个实库检查全部通过。离线分别证明克隆后的外层分片标记进入 CTE 内部、原绑定/WITH 根状态不变、后置应用工厂贯穿 clone/UNION，以及普通 configure 不覆盖前置工厂。显式 scope 的真实用例复用 Decimal、Grouping 和 Calendar 契约，并验证绑定 UNION/CTE 的排序/count/page；普通安装继续通过原连接、报表与单/双 DataSource 分片回归。

IDEA 格式与构建通过，MCP 确认本轮 MySQL search/shard/multisource/window 前缀的测试表遗留为零。两份本批自有上下文诊断日志记录后清理，标准报告保留。该基础单独提交；MySQL 全文原生 fixture 与上层入口在下一批继续，当前基础不能替代完整全文验收。

## MySQL 自然语言与布尔全文来源

提交前检查根上下文基础 `2440e8b652f314fc6194ba9fe7d3d4ac7c77a2e6` 的 [CI 39481724356](https://github.com/kenconnet666/easy_query_pro/actions/runs/39481724356) 为 cancelled，按原状态记录并继续，不将其当作当前候选通过。全文的本地证据与后续候选 CI 分开。

先用 Context7 resolve/query MySQL 8.4 官方文档，再以 InnoDB 真实单列/多列 FULLTEXT 索引验证 natural language 与 boolean 模式，两个 prepare 模式均可绑定搜索文本。原生事务探测确认索引在提交时更新：同事务尚未提交的新文档不命中，提交后命中，回滚文档不会出现。这一差异保留在 MySQL 契约中，不照搬 HANA 的同步索引可见性。

MySqlSearchSpec 保存不可变列集合、搜索词和模式，MySqlSearch 将 MATCH 同时用于筛选与 SEARCH_SCORE Double 投影。根级 OR 先整体包组，原来源只克隆 ClientQueryable；输出 fresh DTO 不继承临时 reader。MATCH 谓词保留数据库非零真值，不增加会改变布尔负贡献结果的 `> 0`。MySqlSearchQueryBuilder 在渲染时强制检查 ProQuerySQLContext 存在；省略 configureQueryScope 或后置工厂未传播标记均清晰失败，不能退成仅构造时检查分片。

测试共用已有 SearchDocument，并把只含 SEARCH_SCORE 的 HanaSearchRow 更名为 SearchHit，供两库共用，避免复制相同 DTO。使用 Maven 正常生命周期仅清理 integration-tests 的旧 APT/编译产物后重建。指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Pmysql,hana -Dtest=MySqlSearchDslTest,HanaSearchDslTest,DslCompilationTest#mysqlSearchRequiresPhysicalTextAndKeepsTheScoreType+hanaSearchRequiresTextColumnsAndKeepsTheScoreType -Dit.test=MySqlSearchIT,HanaSearchIT -Dsurefire.failIfNoSpecifiedTests=false`，14 个离线/消费者检查和 8 个实库用例通过。

MySQL 两种 prepare 的真实对照覆盖短语/前缀/排除/负贡献、引号/Unicode、NULL/未命中、匹配索引、多列、根级 OR、SMART、CTE/count/page、两个评分来源 JOIN/UNION ALL、读取器隔离与原 client 事务提交。HANA 全文四个用例随共享 DTO 更名一起回归通过，包括其原生多评分 JOIN 限制。

IDEA 格式与构建通过，MCP 确认 MySQL `eqp_search_%` 测试表与 HANA `EQP_FULLTEXT_%` schema 遗留均为零。三份自有诊断日志记录后清理，标准报告保留。说明与显式安装步骤见 [MySQL 全文查询](mysql-fulltext.md)；PG 全文类型、查询及完整消费者继续推进。

## PostgreSQL tsvector/tsquery 值类型与缓存读取

核实 MySQL 全文提交 `11a4700953b2a525a283e897c7ba24de3cb39b1b` 的 [CI 39483300377](https://github.com/kenconnet666/easy_query_pro/actions/runs/39483300377) 已成功。本批先交付 PG 值与 JDBC 基础；查询来源模板整理和 PG GIN/匹配/评分按依赖随后实施。

新增 PgTsVector/PgTsQuery 两个编码 record、受控 PgTextSearchType DDL/CAST 描述和两个具体类型 handler，接入原 scalar metadata、value/nativeCast 与显式 configureTypeHandlers。编码通过 PGobject 绑定 native OID，不作为 SQL 片段；普通文本分词和搜索语法交 PostgreSQL。空编码与 SQL NULL 分开，服务器规范化后的编码不伪装成 Java equals 的语义等价。

Context7 核对 PostgreSQL 17 与 pgJDBC 文档后，再读取本机锁定 42.7.14 sources.jar 的 PgResultSet/PgConnection/PGobject 路径：强制两个类型二进制传输时，未注册 binary codec 的非 NULL 对象可能没有文本。处理器明确报错，不转成 SQL NULL。普通 prepareThreshold 0/-1 的文本传输、实体写入/更新/筛选、scalar/Draft/CTE、引号中文、规范化、typed NULL、原生语法错误与错误 JDBC 类型均通过。

两位只读复审指出上游部分内存结果集 getString 直接将 getObject 缓存强转 String。新增模拟真实 PGobject 缓存的离线用例复现 ClassCastException，再把修复限制在全文 handler：用 getObject 读取，核对 PGobject 类型和值，同时接受已验证类型的 String 缓存；没有修改执行器或 PG 时间包装器。最初尝试分组时混合投影常量，先被上游 `unknown aggregate column:ScalarColumn` 拒绝，因此该组合没有被宣称为本批支持。

指定 JBR 17 的正常 verify 通过 7 个离线/消费者检查与 5 个实库用例后，继续收严真实缓存证据：仅设置 CONNECTION_STRICTLY 且仍只有一个执行组会走直接执行捷径。改为 `useShardingConfigure(1, CONNECTION_STRICTLY)`，使两分表经 executeMulti0→inMemoryMerge→DefaultMemoryResultSetRow 物化；以 `mvnw.cmd --batch-mode verify -Ppostgres -Dtest=PgTextSearchTypeTest#cachedDriverObjectsDoNotRequireAnUnsafeStringCast -Dit.test=PostgresTextSearchTypeIT#connectionLimitedShardCachingKeepsNativeDriverValuesReadable -Dsurefire.failIfNoSpecifiedTests=false` 再通过缓存离线 1 项与两个 prepare 实库。最终唯一用例为 7 个离线/消费者及 5 个实库；两个物化用例以最后收严后的结果为准。

IDEA 格式与构建通过，MCP 确认 `eqp_pg_search_%` schema 遗留为零。四份本轮自有诊断日志记录后清理，标准报告保留。API、编码/NULL/二进制与分片边界见 [PG 全文值类型](pgsql-text-search-types.md)，当前候选仍需自己的完整 CI。

## 查询来源快照与渲染边界整理

为 PG 全文来源复用已经在 HANA/MySQL 验收的基础，抽出内部具体 `CapturedQuerySourceBuilder`，删除两份重复的 SearchQueryBuilder。共同类只负责 ClientQueryable 快照、构造期 CTE/表依赖提取、visitor、clone 与同一 ToSQLContext 的子查询渲染；原生匹配/评分、物理来源校验与最终分片检查仍留各自 facade。公开查询 API 不变，HANA adapter 不反向依赖 DSL，也未引入新执行器或 SQL 字符串重绑。

先按 Maven 正常生命周期仅 clean 两个变动方言 DSL 模块，去除已删除内部类的旧产物；再用指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Pmysql,hana -Dtest=HanaSearchDslTest,MySqlSearchDslTest,DslCompilationTest#hanaSearchRequiresTextColumnsAndKeepsTheScoreType+mysqlSearchRequiresPhysicalTextAndKeepsTheScoreType -Dit.test=HanaSearchIT,MySqlSearchIT -Dsurefire.failIfNoSpecifiedTests=false`，14 个离线/消费者检查和 8 个实库用例全部通过。参数顺序、CTE、SMART、克隆、count/page、事务及晚加入分片的边界沿现有测试验收，没有为内部结构增加镜像测试。

IDEA 格式与构建通过；MCP 确认 HANA `EQP_FULLTEXT_%` schema 与 MySQL `eqp_search_%` 测试表遗留均为零。两份本轮自有日志记录后清理，标准测试报告保留。前一 PG 类型提交 `addec1de8cb6e8c319346512cd1ee5981611b3aa` 的 [CI 39490002201](https://github.com/kenconnet666/easy_query_pro/actions/runs/39490002201) 检查时仍 in_progress，未阻塞等待，也未用其代替本批验收。

## PostgreSQL 原生全文匹配、排名与 GIN

继续使用 Context7 resolve/query PostgreSQL 17 官方文档核对 plain/websearch/encoded 输入、rank/cover-density 的 float4 返回值、归一化及 stored generated tsvector + GIN。PgSearchSpec 只保存受控函数模板、原参数和枚举规格，不实现 Java 搜索解析器；配置名按 regconfig 绑定，已编码 PgTsQuery 沿前批 native handler。PgSearch 同层安装 @@ 与 SEARCH_SCORE，输出通过已整理的 CapturedQuerySourceBuilder 进入原 EntityQueryable。

PgSqlExtensions.configureQueryScope 显式安装继承原 PG 工厂的 PgSqlProExpressionFactory，普通 configure 仍保留已有工厂；后置自定义工厂、CTE/clone/UNION 与晚加入分片均验证根标记。原 WHERE 的根级 OR 整体包组，fresh DTO 不继承来源 reader。评分由 float4 显式转换到 Double，只保证结果类型，不增加计算精度。仅否定查询可以匹配且分数为零，因而不以 `score > 0` 替代 @@。

离线 fixture 最初漏装实体显式 Vector/Query handler，在加载 metadata 时被拒绝；按已文档化的安装次序补齐测试初始化，未改变生产 handler 契约。指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Ppostgres -Dtest=PgSearchDslTest,DslCompilationTest#postgresSearchRequiresVectorColumnsAndTypedEncodedQueries -Dit.test=PostgresSearchIT -Dsurefire.failIfNoSpecifiedTests=false`，7 个离线/消费者检查及 4 个实库用例全部通过。

prepareThreshold 0/-1 均覆盖三类输入、全部归一化标志的组合、默认词素权重、原生结果/分数对照、引号/中文/停用词、前缀/短语/否定、NULL vector、空 query、错误语法与不存在配置。English vector 与 Simple 查询不匹配的用例明确语言边界。组合测试覆盖根级 OR、SMART、参数快照、独立 reader、CTE/count/page、两个评分结果 JOIN/UNION、同事务新增可搜索、回滚消失与生成列更新后索引变化。另对完整 DSL SQL 和原绑定执行 EXPLAIN，在独立事务的 SET LOCAL enable_seqscan/search_path 下确认 GIN 可用，finally 回滚恢复；不宣称小表自然规划必选 GIN 或已有性能提升。

只读复审未发现必修生产问题。IDEA 格式与构建通过；MCP 确认 `eqp_pg_search_query_%` schema 遗留为零，两份自有诊断日志记录后清理。前一整理提交 `4b56bbb10723e4443ba23e44cc7c2dac5f7752ca` 的 [CI 39491259410](https://github.com/kenconnet666/easy_query_pro/actions/runs/39491259410) 检查时为 cancelled，按原状态保留并继续推进；当前候选仍需自己的完整 CI。API 与边界见 [PG 全文查询](pgsql-fulltext.md)，后续继续独立消费者、非管理员和成熟运行验收。

## 独立 Java JAR/POM/APT 消费者

开始本批时核实 PG 全文提交 `394dd21ad84a69e3fd91bd9837ccd890304e698e` 的 [CI 39492769759](https://github.com/kenconnet666/easy_query_pro/actions/runs/39492769759) 已成功。先在计划中固定 G6 的产物、Spring 与可靠性顺序，再参考 Apache Maven Invoker 3.10.1 的 [产物暂存](https://maven.apache.org/plugins/maven-invoker-plugin/examples/install-artifacts.html)、[独立 clone](https://maven.apache.org/plugins/maven-invoker-plugin/examples/clone-projects.html) 和 [构建后检查](https://maven.apache.org/plugins/maven-invoker-plugin/examples/pre-post-build-script.html)，新增只由 consumers profile 激活的内部测试模块；没有把 Spring、池或驱动加到生产模块的必需依赖。

`examples/java-client` 有独立 POM，没有本仓库 parent、relativePath、sibling 源码或集成测试依赖。Invoker 暂存候选 JAR、真实 POM 与可达父 POM到模块 target repository，然后 clone 示例并启动新 Maven clean verify。本轮核对五个暂存 Pro JAR 的 SHA-256 均等于刚打包的模块产物；消费者进一步断言库类从 JAR 加载、自己的 ProductProxy 新生成、仓库 TestClients 不在 classpath。三个后端的共同 DSL 与原生类型 API 均从发行边界编译，而非沿 IDEA/原 reactor 的生成目录通过。

另两个独立负例分别漏选 PG 模块、请求未暂存的 HANA 版本。前者要求真正的 PostgreSQL 包编译诊断；后者固定 offline 并核对指定 artifact/version 的解析错误。两者都用 post-build hook 判定原因，不把任意构建或网络失败算作预期成功。默认执行 `mvnw.cmd --batch-mode verify -Pconsumers -pl consumer-tests -am`，三个独立构建均通过；正例包含 4 个离线检查，真实数据库用例按默认 none 明确跳过。

随后用相同命令分别追加 `-Dconsumer.database=mysql`、`postgres`、`hana`，每个正例均为 5 个检查全部通过、零跳过，并重复通过两个构建负例。实库覆盖从独立实体生成的 CRUD、Unicode/DECIMAL、NULL-safe、CAST、分页、原 EasyQuery 事务回滚和 Hikari 连接恢复。MySQL 首轮触发上游默认物理删除保护，测试只在带主键条件的自有表删除链添加 allowDeleteStatement(true)，未改客户端默认保护。三库最终都断言只余 fixture owner 借出，关闭 owner 后活动连接归零，复用连接 autoCommit 正确。

CI 新增 Windows/Linux 独立消费者任务，并在原 MySQL/PG/HANA 数据库 job 中复用既有实例运行对应实库消费者；没有新增数据库服务，原内存限制和 HANA Node 验证步骤保持。actionlint、IDEA 格式和现有工程构建通过；独立示例的实际编译证据来自 Invoker。MCP 确认三库 `EQP_CONSUMER_%` 表遗留均为零。五份本轮根目录诊断日志记录后清理，标准 Invoker/测试报告与 target 内隔离构建缓存保留供后续 Spring 消费者复用，并由正常 clean 生命周期管理。使用入口见 [独立 Java 消费者](../examples/java-client/README.md)。

## 默认 converter 安装保留应用实例

Spring 接入复审发现上游按具体类拒绝重复 ColumnValueSQLConverter，不能把默认安装当作覆盖操作。先在 MySqlJsonDslTest/PgJsonDslTest 添加直接回归，均复现 `column value sql converter:...,repeat`；PG 额外验证只注册 JSONB 时仍应补齐 JSON，而第二次安装必须保留两者身份。

将三个默认映射的注册收敛到既有 ExtensionInstaller 的小型 installDefaultMapping 方法，通过上游公开 get/apply API 检查并补缺。MySqlExtensions/PgSqlExtensions.configureMappings 现为幂等默认安装，已注册的同类实例保持；没有改变上游主动重复注册的错误行为，没有删除或反射改写应用注册表。

指定 JBR 17 执行 `mvnw.cmd --batch-mode test -Dtest=MySqlJsonDslTest,PgJsonDslTest -Dsurefire.failIfNoSpecifiedTests=false`，两个新红测与四个已有 JSON 契约全部通过。IDEA 格式与构建通过；只涉及初始化注册，未重复无关实库测试，两个自有诊断日志记录后清理。公共契约修复独立于后续 Spring 示例提交。前一消费者提交 `70098e247dadeccc673c02e7df16b20af5d59ae4` 的 [CI 39495405959](https://github.com/kenconnet666/easy_query_pro/actions/runs/39495405959) 检查时 cancelled，按原状态保留，不视为当前候选成功。

## 独立 Spring 接入、事务与启动失败清理

新增独立 `examples/spring-client`，锁定 Boot 3.5.16 / Framework 6.2.19、JDK 17 与原 sql-springboot-starter 3.2.17。通过 Context7 resolve/query 与 [官方配置测试说明](https://docs.spring.io/spring-boot/3.5/reference/features/developing-auto-configuration.html) 核对 ApplicationContextRunner、条件 Bean 与 [Java 版本要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)，再对照上游 starter 的真实源码时序。

SQL92 对应空 DefaultDatabaseConfiguration，作为 HANA 完整配置的中性入口；UNKNOWN 会在 StarterConfigurer 之前被拒绝，专门负例确认钩子没有执行。显式 EasyQueryClient Bean 仍调用原 SpringBootStarterBuilder，没有复制其 options 或连接实现；EasyEntityQuery 继续由上游自动配置产生。先注册默认 handler，再调用原应用组件初始化器，最后用已独立修复的 configureMappings 补缺。标准组件/route 注册不主动加载实体 metadata，真实带 @Table 的 JSON 字段元数据断言应用 JSONB 与默认 JSON 均使用正确实例。

初版正常路径已在三库各通过 5 项。只读复审随后发现两项初始化问题并做红测：普通 PgJsonbColumnConverter Bean 引发重复注册，client 未返回成 Spring Bean 时的组件异常没有销毁回调接管后台任务。后者使用 start-time-job=true 的真实定时线程，并分别注入正常清理与清理再次抛错；红测 finally 也主动关闭线程，未靠进程退出掩盖泄漏。公共 converter 修复单独提交后，示例调整注册顺序，并在 build 后失败时停止本 client 的 jobs/executor。原配置异常身份保留，清理异常以 suppressed 附加；timer 清理失败也继续关闭 executor。

最终指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Pconsumers -pl consumer-tests -am -Dinvoker.test=examples/spring-client -Dconsumer.database=<mysql|postgres|hana>`，三库独立构建各 8 项全部通过、零跳过（7 个离线/初始化检查与各库 1 个实库契约）。@Transactional 用例先由 JdbcTemplate 插入未提交行，立即由 EasyQuery 读取，再由 EasyQuery 写入并统一回滚；最大两连接且 fixture owner 占一连接，另以 TransactionTemplate 验证共同提交。测试同时检查原 schema/结果上限、应用 handler、HanaOptions 身份、APT/JAR、归还/autoCommit、Spring 关闭池及 worker。

IDEA 格式与现有工程构建通过；独立示例以 Invoker 的实际编译/运行验收。MCP 确认三库 `EQP_SPRING_%` 表遗留为零，九份本轮根诊断日志记录后清理，target 中标准报告与隔离缓存继续复用。consumer-tests 已纳入新示例，既有独立消费者和三库 CI 步骤会自动执行它，无需新容器。前一公共修复 `731cc9561355218c1c277d35780cdaace34c2b33` 的 [CI 39498305188](https://github.com/kenconnet666/easy_query_pro/actions/runs/39498305188) 检查时仍 in_progress，未等待。使用约束见 [Spring 消费者](../examples/spring-client/README.md)；其他 Spring 主版本、JTA 与多事务管理器不自动列入兼容声明。

## HANA 旧工厂与显式字符串去重组合

按 67 个上游工厂审计补齐四个小型实库用例，没有新增同义函数或改动生产 SQL 翻译。旧 round 验证默认表与显式 SQLTableOwner、正负数、负 scale 和 NULL。SQLFunc.subQueryValue / exists 的 Query 与 selector 两重载直接接受 selectColumn 返回的核心 Query，保留相关引用、参数、clone 和 count；EXISTS 进一步验证 NOT、无匹配与分页。

字符串去重使用已有投影→DISTINCT→CTE→普通 joining，实际验证结果、聚合内排序、含引号/中文/模板花括号的分隔符绑定和空输入 NULL。直接 joining(distinct=true) 的拒绝负例继续保留；该 API 自动改写尚未完成，没有把显式组合记成同一入口的支持，也没有解析 SQL 或新建查询引擎。

指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Phana -Dtest=HanaConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=HanaScalarFunctionsIT#legacyRoundFactoryKeepsTableOwnersNullsAndNegativeScale,HanaQueryCompositionIT#scalarFactoryOverloadsKeepCorrelationBoundValuesAndNullResults+existsFactoryOverloadsComposeWithNotCountAndPaging,HanaAggregateFunctionsIT#distinctStringAggregationUsesAnExplicitProjectedRelation`，2 个基础离线检查及 4 个新实库用例通过。IDEA 格式与构建通过；全部使用小型常量关系，没有创建数据库对象，三份本轮诊断日志记录后清理。前一 Spring 提交 `03d2df909c003bcb56e0bee3505fa8403823f08e` 的 [CI 39499541147](https://github.com/kenconnet666/easy_query_pro/actions/runs/39499541147) 检查时 cancelled，仍按原状态记录；当前批次交后续完整 CI。

## 订单指标、部门 Top N 与十三月同比

本批只复用已有 API，新增 ReportOrder fixture / OrderReportContract，并扩展既有 CalendarReportContract，不修改生产函数或再造报表入口。只读研究先核对 SUM.filter 使用 ELSE 0 且 filter 原地修改聚合对象，因此每个指标重新创建聚合，结果保留无匹配、匹配金额全 NULL、混合未匹配行与空输入之间的差异。

13 行订单覆盖付款/退款计数与金额、付款客户去重、非 NULL 金额计数、零/NULL 数量、真实 NULL 部门。输入先 CAST 为 DECIMAL 再 SUM/除法；另两行各 1,500,000,000 数量的用例精确得到总数 3,000,000,000 和单价 1。付款总额 130 在分组之后使用全局 SUM 窗口，A 的展示筛选不改变份额分母。部门 Top N 沿上游 rank/dense-rank/row-number，仅 row-number 加 id 次序；第一名分别保留 5 个并列结果或 4 个唯一结果，count/page 与显式 SMART 均验证。

日历部分重用原有绑定事实生成器，覆盖 13 个连续月后 LAG(1)/LAG(12)、正基数与真实零基数。2025 年一月金额 60、上月 20、去年同月 40 时，环比为 2、同比为 0.5；去年金额为 0 时同比为 NULL。缺月金额/count、原始稀疏 LAG(12) 反例、直接日期 EQ、同比 NULL 筛选、clone 与第三页结果均有固定断言，四月旧契约随 helper 整理一起回归。

指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Phana,mysql,postgres -Dtest=HanaConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`，failsafe 定向选择三库 ReportingIT 的 conditionalOrderMetricsAndTopRanks、yearAndMonthComparisonsUseCalendarOffsets 与 calendarMonthsKeepEmptyBucketsBeforeLagAndPaging，2 个基础离线检查与 9 个实库用例全部通过。只读复审后将 D 组的两笔数量改为 1/3，金额仍为 30/10，确保简单平均单笔价格与加权均价不同；再次定向通过三库订单用例，其余六个日历用例未改动。IDEA 格式与构建通过，全部为少量常量关系，没有创建数据库对象。两份本轮根日志记录后清理，标准报告保留。

前一 H08 提交 `1d9356972a5039f4915f29c5bbfb90746a8cd116` 的 [CI 39503146808](https://github.com/kenconnet666/easy_query_pro/actions/runs/39503146808) 已成功，当前候选仍需自己的 CI。业务说明见 [订单报表](reporting-orders.md) 与 [时间序列](reporting-time-series.md)。全局排名构造器拒绝空 partition 的真实缺口将沿既有 WindowSpec/WindowFunctions 单独补齐，不假称本批已提供该入口。

## 克隆后晚加入分片的共同窗口保护

准备排名桥时发现既有 ScopedSegment 只检查构造时的 ExpressionContext。先用 SUM 窗口构造→clone→仅外层 useSharding 的小型离线用例，三库均复现没有拒绝；克隆产生新 context，而已捕获的内部 segment 仍保留旧 context。

在已经安装的 ProExpressionBuilderFactory 增加最终查询检查，不替换用户的原生 SQL ExpressionFactory。专用 ServerEvaluationVisitor 沿 Pro 标记和公开 builder/segment 接口进入派生关系、CTE 声明、UNION、标量子查询；另检查完成上游裁剪后的实际关系和 ON，不扫描未使用的导航关系。共享或递归声明以对象身份访问集守环。普通语义 visitor、原生渲染和参数委托保持；服务器运算标记不写回 context，因此移除窗口投影后的普通 count 不受影响。

修复后的用例覆盖失败 clone 不污染原查询、CTE/UNION/原生标量子查询和派生 JOIN 的 ON、普通分片 CAST、已去除窗口的 count。首次扩展 fixture 错把两次 backend.client() 当作同一 client，HANA UNION reader 身份检查正确拒绝；改为一个 client 创建独立来源，没有放宽生产映射契约。

指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Phana,mysql,postgres -Dtest=ShardingExpressionScopeTest,WindowDslTest,MySqlQueryContextTest -Dsurefire.failIfNoSpecifiedTests=false`，并定向三库 ReportingIT 的 windowFramesAndGroupedReports，40 个离线检查及 3 个实库用例通过。只读复审无新的必修项，IDEA 格式与构建通过。MCP 确认本轮三库窗口测试 schema/表遗留为零，两份自有根日志记录后清理。

前一 A01 提交 `8f7adb546f8eb86c4a15d3496d95daffdc48b93c` 的 [CI 39506558426](https://github.com/kenconnet666/easy_query_pro/actions/runs/39506558426) 检查时为 cancelled，按真实状态保留且不等待。此共同修复独立提交，随后继续全局排名入口。

## 共同全局排名与分组后排名

对照上游 3.2.17 的三个 builder 及核心排名函数，均要求非空 partition，确认不是缺少消费示例。通过 Context7 resolve/query 核对 PostgreSQL 17、MySQL 8.4 和 HANA Platform 官方窗口规则后，只增加 ProDsl 的 rank/denseRank/rowNumber 三个薄入口：继续使用 WindowSpec 的分区和排序，返回原 NumberTypeExpression<Long>，并复用 ExpressionScope.window 的行数与计算层标记。

排序必须明确提供，frame 直接拒绝，不接受一个会被数据库忽略的 ROWS/RANGE。规格子类的 SELF 链和已有 view(result, factory) 保留自定义能力，没有新增排名 AST、类型族或执行器。离线与消费者覆盖 Long 静态类型、全局无伪分区、不可变规格、缺少排序/frame/嵌套窗口、跨 client/查询来源、clone 与晚加入分片。

三库实库沿现有订单 fixture 分别验证七行全局与部门排名。id 1/9 的 RANK 为 3、id 2/8 为 5，id 8 的稳定 ROW_NUMBER 为 6；直接外层 id/部门 EQ 和名次筛选保持全局结果。部门先合计得到 50/40/40/0 后，RANK 为 1/2/2/4，DENSE_RANK 为 1/2/2/3。明确 SMART、CTE、参数克隆、count/page 和空输入均通过，原有订单契约随 helper 复用回归。

指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Phana,mysql,postgres -Dtest=WindowDslTest,DslCompilationTest#rankingWindowsKeepLongResultsAndCustomSpecificationTypes -Dsurefire.failIfNoSpecifiedTests=false`，failsafe 选择三库 ReportingIT 的 globalRankingPreservesPeersAndGroupedReportBoundaries 与 conditionalOrderMetricsAndTopRanks，29 个离线/消费者检查及 6 个实库用例通过。IDEA 格式与构建通过；小型常量来源没有创建数据库对象，单份本轮根日志记录后清理。

前一共同修复 `00fe3433f63b4656f4fbfebbdd967e450e39eaa5` 的 [CI 39508054652](https://github.com/kenconnet666/easy_query_pro/actions/runs/39508054652) 检查时仍 in_progress，没有阻塞等待。调用与边界见 [窗口规格](dsl-windows.md) 和 [订单报表](reporting-orders.md)，后续继续 G6 资源与权限验收。

## 长结果读取失败、资源计数与并发 reader 隔离

先将 G6.2/3 的取舍写入计划，复用已有 LOB/identity 并发验收，不重复相同场景。新增 QueryResourceContract 与仅测试使用的 JdbcResourceProbe，三库各自创建随机物理表/schema，2,049 行包含名称和金额 NULL；不新增生产执行器。Context7 resolve/query 核对 pgJDBC 与 Connector/J 的 cursor 条件后，PG 流查询使用显式事务、fetch 64 和 forward-only，MySQL 专用源开启 cursor/server prepare 并禁止静默降级。

首轮 2 个基础离线及 3 个实库用例通过。只读复审指出仅在归还连接后检查关闭会被 Hikari 的 closeStatements 兜底掩盖；据此将 stream 完整读取、提前 65 行退出和第 129 行 reader 异常的检查提前到 rollback 之前，此时活动连接必须仍为 1，所有已取得 JDBC 句柄已经关闭。原回滚后 active=0、count=2049、autoCommit/readOnly 复用检查继续保留。

最终以指定 JBR 17 执行 `mvnw.cmd --batch-mode verify -Phana,mysql,postgres -Dtest=HanaConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=HanaQueryResourceIT,MySqlQueryResourceIT,PostgresQueryResourceIT` 再次全部通过。共同 client 的四 worker 使用各自 fresh query/reader/CTE，24 轮正确隔离标签与 NULL，额外一次 reader 失败不污染后续查询。预热后每库 34 条业务 SQL 与 34 次 JDBC execute 一致；完整流 id 校验和为 2100225。

HANA 观察到 68 个 ResultSet 门面，MySQL/PG 为 34。核对本机 Hikari 7.0.2 sources 与 HanaJdbcSQLExecutor 的 executeQuery/getResultSet 路径，确认这是同一底层结果的两种包装入口；所有门面均关闭，不把它解释为双倍 SQL。最终测量段 HANA/MySQL/PG 分别为 668/1231/157 ms，仅作本机样本，详细环境与边界见 [查询资源验收](query-resources.md)。

IDEA 格式与构建通过，MCP 确认本轮三库自有表/schema 遗留为零，两份根诊断日志记录后清理。前一排名提交 `72e46b1cdfc780d808aadf2ec3cded56b836ce78` 的 [CI 39508644916](https://github.com/kenconnet666/easy_query_pro/actions/runs/39508644916) 为 cancelled；主线程随后确认包含排名与共同分片修复的后续提交 `1755a6d59484849285e72eae69a3552780ffd5d2` [Java CI 39508698033](https://github.com/kenconnet666/easy_query_pro/actions/runs/39508698033) 成功。该基线不包含当前资源批，当前候选仍交自己的完整 CI。
