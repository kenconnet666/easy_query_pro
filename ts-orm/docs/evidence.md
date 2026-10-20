# TypeScript ORM 验证记录

## 2026-10-21：基础纵向实现

工程使用 Node 24.18.0、TypeScript 7.0.2、pnpm 11.22.0。核心没有运行时 ORM 依赖；pg 8.23.1、mysql2 3.24.6 通过独立驱动子路径适配。schema/表达式/关系/编译/会话分别维护职责，表定义不自动创建数据库表。

首批已完成：

- 无生成器 schema，Row/Insert/Update 推导，SQL 类型族与 JS 载体分开。
- 不可变查询分叉、INNER/LEFT JOIN、真实独立自连接、显式投影、派生关系、排序/limit/distinct/count。
- client 级具体扩展命名空间、表达式 mount、结构化函数 renderer；不依赖全局 augmentation。
- 显式事务连接、回滚/释放异常保留、提交开始前关闭业务入口。
- PostgreSQL/MySQL 原驱动执行，精确金额和 BIGINT 不先经 JS number。

只读审查给出的重排参数、可空业务映射和派生子类 API 问题，分别在编译结构与类型边界修正。函数 renderer 现返回节点，最终输出顺序决定参数顺序；重复节点会产生对应的重复绑定。派生列保留 T、SQL kind 和 codec，额外子类方法需要重新 mount，避免静态类型承诺不存在的方法。

回归先复现了三个失败：INTEGER 接受超过 32 位的值、withExtensions 重建原 namespace 实例、COMMIT 未完成时逃逸 tx 仍可提交查询。修复后 23 个单元行为用例及类型正负例通过。

两个真实数据库契约在已有本地实例执行：PostgreSQL 17.11、MySQL 8.4.11。覆盖精确 DECIMAL、大于 2^53 的正负 BIGINT、Unicode/引号、LEFT/self JOIN、业务 codec 派生重排、函数参数重排/重复、分页后 count、limit(0).first、事务内写入可见与回滚。MySQL 刻意开启连接池 decimalNumbers，并在 NO_BACKSLASH_ESCAPES 下验证服务端绑定，仍保持精确值及匹配行为。

MySQL 实测暴露文本 CAST 的 collation 冲突：列默认 utf8mb4_0900_ai_ci 与连接 utf8mb4_general_ci 组合时，CHAR CAST 改变参数的强制转换性。修复位于共同方言参数入口，文本保留裸预处理占位符，未通过修改数据库/连接 collation 隐藏问题。规则依据见 [MySQL coercibility](https://dev.mysql.com/doc/refman/8.4/en/charset-collation-coercibility.html)。

每次实库用例只创建随机 eqts_ 表，finally 删除并关闭本次创建的连接池。持久开发实例未删除或重启。完整 CI 独立配置为四个 Node/OS 检查任务和两个数据库任务，使用既有固定镜像与内存限制；当前候选提交仍需其自己的 CI，不能以本地结果冒充完整流水线通过。

当时尚未完成的 CTE/UNION 与类型化写入见后续批次证据。完整聚合/窗口/关系加载、干净消费者和完整性能验收继续按 plan.md 推进，当前包保持 private，未发布到 npm。

## 2026-10-21：HANA Node 驱动

基础提交 f62038127e2caf3d802a0b00f9b392e352d789b3 的独立 TS CI [39467210085](https://github.com/kenconnet666/easy_query_pro/actions/runs/39467210085) 六个任务全部成功。后续 Java 提交 549a37bafc7cf49daffe586d8f143d69a09f2add 的 Java/三库 CI [39468072110](https://github.com/kenconnet666/easy_query_pro/actions/runs/39468072110) 成功；这两个结果不替代新增 HANA Node 代码的 CI。

本地使用 SAP 官方 @sap/hana-client 2.30.27，在现有 HANA 2.00.088 实例验证与其他驱动相同的查询/codec/事务契约。精确金额和超过 2^53 的 BIGINT 保留原值；HANA 查询参数在最终遍历时绑定，SQL 标识符一致引用。共享契约增加提交成功和归还后自动提交场景，HANA 限制池只保留一个连接，迫使其重用，确认上一事务状态不泄漏。结束时在 finally 删除本次随机表，并检查池内借出连接为零后清理池。

HANA driver 使用 SDK 完整结果 API，不暴露需要继续占用连接的游标；驱动对象只借用应用池。归还时调用 disconnect，依据 [SAP 连接池清理规则](https://help.sap.com/docs/SAP_HANA_CLIENT/f1b440ded6144a54ada97ff95dac7adf/f7bfbd320dcd4170a78d800362c98bd0.html) 由池回滚未结束事务并重置连接，不用 setAutoCommit(true) 隐式提交失败事务。显式池使用 poolCapacity/maxConnectedOrPooled 配置。@sap/hana-client 原生包的构建脚本已检查并单独允许，其余依赖未开放任意构建。

HANA Node CI 与既有 Java HANA/HDI 契约共用同一受限容器和临时凭据；PG/MySQL 保持独立并行任务。新增提交的流水线状态需按该 SHA 单独检查。

## 2026-10-21：公开类型与不可变边界审查

公开 DataType 构造入口的可空参数现在受值泛型约束，非空 T 不能启用 SQL NULL；storage 参数统一在构造边界校验，避免自定义类型绕过标准工厂产生非法长度或精度。schema、投影、来源与扩展命名空间拒绝 symbol/non-enumerable 键，同时保留类型负例，避免类型中存在而 SQL 结果缺失的字段。

DataType/Column/Table 的基类不再提前冻结派生实例，允许扩展类初始化自身字段；自身拥有的描述做独立不可变快照。orderBy 同样快照排序项，调用方修改旧的 Order 不会反向改变已创建的查询。26 个单元用例和类型正负例通过，完整矩阵继续交 CI。

## 2026-10-21：CTE、UNION 与 HANA 三值逻辑

8f60c20d4b9436afba6537a1337ec6d952973431 的 [TS CI 39470891563](https://github.com/kenconnet666/easy_query_pro/actions/runs/39470891563) 与 [Java/三库 CI 39470891593](https://github.com/kenconnet666/easy_query_pro/actions/runs/39470891593) 全部成功，后者包含新增的 HANA Node 契约。

CTE 复用冻结投影与来源身份，编译先收集依赖再按最终 SQL 绑定参数。WITH 始终位于顶层，count 不在派生表内部生成 WITH。UNION 每个槽核对 key/顺序/storage/codec，结果是一个新来源，外层不能继续捕获左分支的表列。结构参照 [Kysely 的集合操作](https://github.com/kysely-org/kysely/blob/master/src/parser/set-operation-parser.ts) 和 [Drizzle 的字段顺序检查](https://github.com/drizzle-team/drizzle-orm/blob/main/drizzle-orm/src/pg-core/query-builders/select.ts)，没有引入这些库作为依赖。

审查复现同一个业务 mapping 叠在不同底层 codec 时，旧的最终 identity 比较会无声读错值。身份现保留完整映射链，nullable 不改链；不同链拒绝 UNION，相同链的可空性按并集处理。

本地三库验证依赖 CTE、自连接、CTE 外层 count/分页、分支 limit 与外层 limit、UNION ALL 重复、UNION 去重（包括 NULL）、混合集合树、精确金额与 BIGINT。HANA 首次因 ORDER BY 中裸 IS NULL 报 SQL 语法错误；修复在编译器的条件/标量边界，值位置用三值 CASE，WHERE/JOIN 的 BOOLEAN 标量显式比较 TRUE。再次三库验证通过，比较投影分别返回 true/false/null，未把 UNKNOWN 强行归为 false。36 个单元用例与类型负例覆盖完整链兼容、字段异序、作用域捕获和最终参数顺序。测试自有表均经 finally 删除，持久实例保持运行。

后续审查补上原生 renderer 的整体括号，保证复合 OR 参与 NOT、原生加法参与乘法时保持表达式树的优先级；新增三库 NULL/false/true 结果验证与单元反例，共 37 个单元用例通过。add8b8e 的 TS CI 两个数据库任务成功，四个代码任务因 composition.test.ts 一处链式格式不符合检查而失败；已规范该处并在本地重新运行 format:check，通过后继续提交，未关闭检查。

## 2026-10-21：类型化写入与共享执行基础

afd877800d82f2cf424bce4e3289b18dcb0c4622 的 [TS CI 39473357057](https://github.com/kenconnet666/easy_query_pro/actions/runs/39473357057) 六任务全部成功，证明上述格式问题已恢复。后续 Java 变更使同提交的 Java CI 被取消，不能称其全部流水线通过。

新增单表 INSERT/UPDATE/DELETE、连续列形状批次和 PG 原生 RETURNING。查询和写入共用 compiler/expression、compiler/projection 与 Session，不复制参数、scope、decoder 或事务资源管理。Column 的选项泛型直接对应 runtime metadata，生成列的 readonly 与 nullable/defaulted 的省略含义分开；显式 metadata 类型不能缺少运行时 options。

所有行在 acquire 前完成校验。HANA 拒绝任何零显式列行，只执行正常单行 INSERT；未使用 identity override、虚假列或读取会话最后 identity 的模拟。MySQL/PG 连续相同形状合并为至多 100 行 VALUES，其他形状保持输入顺序。整批回滚使用显式 transaction，自动提交的部分成功以 WriteExecutionError 的已确认计数表达。影响行数保留 driver 的数据库语义。

共同 UPDATE 拒绝依赖另一个同时被赋值列的表达式。MySQL 单表赋值通常从左到右，PG 使用旧行值，不能靠调整对象键顺序抹平。[MySQL UPDATE](https://dev.mysql.com/doc/refman/8.4/en/update.html)、[PG UPDATE](https://www.postgresql.org/docs/17/sql-update.html)

三库真实验证默认值与 SQL NULL、生成身份列、业务 codec、精确 DECIMAL/BIGINT、UPDATE 本列自增、DELETE、正常批次、第二行唯一约束失败后的事务回滚与连接重用。PG 额外验证三个写操作的 RETURNING 投影重排，并让 decoder 在写入成功后主动失败，确认事务回滚。PG/MySQL 默认值空插入也有实测，HANA 同一入口仅验证提前拒绝。

审查补齐链式 SET 的重复物理列检查、构造选项的类型约束和 200,000 行 RETURNING 的非展开累积。48 个单元用例及类型正负例通过，包含编码一次且保持输入快照、全计划先拒绝、批次只借一次连接、失败停止以及逃逸事务拒绝。IDEA MCP 额外检查三库 eqts_ 临时表数量均为 0；共享容器、卷与持久数据库保持不变。T09 的关系加载、参数/SQL 大小限制及 T07 报表继续推进，本批不宣称完成整个 ORM。

## 2026-10-21：分组、条件聚合与数值范围

b39ffbb8a0c4fed9073432ad933a21c7b4f9290d 的 [TS CI 39476884005](https://github.com/kenconnet666/easy_query_pro/actions/runs/39476884005) 与 [Java/三库 CI 39476883972](https://github.com/kenconnet666/easy_query_pro/actions/runs/39476883972) 全部成功；随后 HANA 全文提交 086aadd8ecaf2c70fc3ac76faae3fa20c0e8deb4 的 [CI 39479458044](https://github.com/kenconnet666/easy_query_pro/actions/runs/39479458044) 也成功。

本批加入结构化 aggregate/CASE/COALESCE/NULLIF/CAST，命名 GROUP BY、HAVING，以及 SELECT/HAVING/ORDER 的分组范围校验。既有 call 扩展的聚合/窗口参数也必须通过逐行语义检查；不会通过外层 CAST 或 CASE 隐藏嵌套聚合。键对象 getter 重复求值会生成不同身份的计算表达式，该问题已先复现失败，再以一次快照修正。

SUM 在累加前提升普通输入，累计来源在下一层直接保持宽中间值；computed 来源要求显式 nativeCast，信息穿过 CTE/UNION 后仍有效。DECIMAL 共同 scale 上限修正为 30，参数及读取都核对有效数值范围，未转为 JS number。类型推导为 COUNT bigint 非空、SUM decimal 字符串可空、MIN/MAX 原 codec 可空。

三库真实表验证 6 条事实的全局/分组/条件聚合、LEFT JOIN 的 COUNT(*) 与 COUNT(right.id)、空输入报告行数、HAVING 后 count、两层 SUM、CTE/UNION 组合、CASE 的 UNKNOWN 分支以及 NULLIF。INTEGER 求和 4294967295、BIGINT 求和 18014398509481987、源 DECIMAL(19,4) 扩大到 1999999999999999.9998 都保持精确。合法 38 位值可读；39 位累计在 HANA 报明确 overflow，在 PG/MySQL 由 RangeError 导致 projection decode 失败，均未返回夹值。57 个单元用例与类型正负例通过，完整矩阵交对应提交的 CI。
