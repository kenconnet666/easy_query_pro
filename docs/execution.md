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

## 下一步

1. 检查新提交的精确类型 DSL CI，继续 HANA 未关闭的函数/写入边界与其他通用类型。
2. 继续收敛 G0 高风险接口：计算视图正式查询源和参数绑定 SPI、类型转换器以及编译负例。
3. 完成函数审计、类型桥接的转换器/编译负例与 SQL 组合失败覆盖，明确不等价能力与剩余风险。
4. 按依赖进入完整 HANA 函数/查询/类型实现，再实施通用 DSL 与三库类型增强。
