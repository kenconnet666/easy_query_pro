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

## 2026-10-21：ROWS 窗口与多层报表

142bf953fc070a5bf9271ec1e3a1577f286ad1dc 的 [TS CI 39481817382](https://github.com/kenconnet666/easy_query_pro/actions/runs/39481817382) 六任务成功；Java CI 后被新提交取消。继承其 TS 报表代码的 MySQL 全文提交 11a4700953b2a525a283e897c7ba24de3cb39b1b 的 [Java/三库 CI 39483300377](https://github.com/kenconnet666/easy_query_pro/actions/runs/39483300377) 成功。

窗口是结构化 Node，裸聚合 over 与独立的排名/值窗口共用 WindowSpec。窗口内的聚合不把外层错误变成全局聚合；普通分组后使用派生来源明确进入下一层。数值来源与业务 codec 继续穿过窗口输出，NULLS 排序在普通/窗口上下文复用一个渲染路径。

真实三行 [10,NULL,30] 验证 lag1/default999 得 [999,10,NULL]、lead1 得 [NULL,30,999]、lag0 保留原值；following1 单行 frame 的 count(*) 为 [1,1,0]、count(value) 为 [0,1,0]、sum 为 [NULL,30,NULL]。FIRST/LAST/NTH/MIN/MAX 的尾部空 frame 均为 NULL。额外非空权重 [10,20,30] 证明默认 peer 累计 [30,30,60] 与显式 ROWS [10,30,60] 的区别；排名、无 ORDER 的 ROW_NUMBER、全局/分区窗口及四种 NULLS 方向均通过三库。

分组日汇总 → CTE → 累计/全局值/LAG → 外层筛选已验证，显示第二个桶仍保留累计 60、全局 60、前桶 30。DISTINCT 窗口输出的 Query.count() 正确为 2，未删掉窗口投影。

反向 ROWS 原型确认 PG/MySQL 原生返回空 frame，HANA88 报 Invalid window frame specification；移除诊断输出后保留明确的跨库负例，共同 API 拒绝反向范围。65 个单元用例、类型负例和三库实测通过。偏移值采用项目 int32 范围的受控字面量，不把这个共同上限冒充各引擎最大值。

另以纯内存 format(format(source)) 复现 Prettier 3.9.10 对部分 TS 链式表达式首遍不稳定；独立工具提交 cb45469 增加有限收敛检查，只重写仍有差异的文件，CI 原格式检查保持开启。

## 2026-10-21：分页与事务选项

bfe1e93bb7cace3823b99165368a8caad6a0063c 的 [TS CI 39487509280](https://github.com/kenconnet666/easy_query_pro/actions/runs/39487509280) 与 [Java/三库 CI 39487509355](https://github.com/kenconnet666/easy_query_pro/actions/runs/39487509355) 均成功；它们验证上一窗口批，不替代本批证据。

page 先编译两个完整计划，再同租约顺序执行 COUNT 与当前页。总数/总页数保持 bigint，参数和结果 codec 沿用编译器。明确要求顺序与正整数，拒绝已有 limit/offset；COUNT 等待期间如果事务结束，不再启动数据查询。

三库真实并发测试在 COUNT 后由第二独立租约插入并提交：READ COMMITTED 返回 total=1、rows=[1,2]，REPEATABLE READ 返回 total=1、rows=[1]。SERIALIZABLE 分页单独验证，不让 MySQL 范围锁等待测试自身。HANA 使用两个独立容量为 1 的测试池，主池三种隔离通过 CURRENT_TRANSACTION_ISOLATION_LEVEL 验证，在归还后恢复默认值。

只读访问模式探针确认 HANA READ ONLY 会跨提交和池归还保留，后续 INSERT 报要求先 SET TRANSACTION READ WRITE。诊断探针已恢复访问模式并删除，正式 API 改为 acquire 前拒绝 HANA pooled adapter 的 readOnly 选项；PG/MySQL 则验证只读读成功、写拒绝、失败后普通写仍能提交。

PG 事务先插入、捕获除零错误再正常返回的用例，旧实现红测为 Missing expected rejection；COMMIT command tag 检查后正确抛出回滚失败，并确认插入不存在。MySQL completion_type=CHAIN 和 RELEASE 两种会话设置都验证 COMMIT/ROLLBACK 后的普通写立即对另一租约可见，原会话设置在 finally 恢复；不依赖其他数据库才有的事务状态变量。

本地 74 个单元用例、类型正负例、构建及格式检查通过，三库契约按改动运行，包含分组 CTE → 窗口 CTE → 外层过滤的真实分页。测试随机表与两个自有 HANA 池在 finally 清理，持久实例保持运行。自定义驱动未声明 capability 时显式选项提前拒绝，设置失败不会调用业务回调，MySQL 下一事务设置后 START 失败会销毁连接。

## 2026-10-21：原生存储协议与 PG UUID

9abb12d08a70885fe879866cccb7bdedce7ff93a 的 [TS CI 39491494614](https://github.com/kenconnet666/easy_query_pro/actions/runs/39491494614) 六任务成功，同 SHA 的 Java CI 后被新提交取消。随后 PG 全文查询提交 394dd21ad84a69e3fd91bd9837ccd890304e698e 的 [CI 39492769759](https://github.com/kenconnet666/easy_query_pro/actions/runs/39492769759) 成功，包含此前分页与事务的 HANA Node 验证。Java PG 类型提交 addec1de8cb6e8c319346512cd1ee5981611b3aa 的 [CI 39490002201](https://github.com/kenconnet666/easy_query_pro/actions/runs/39490002201) 也成功。

native descriptor 保存在 DataType.storage，快照 identity、各方言两个语法片段与比较能力。Expr 的输出存储也标记到 Node，低层 fragment 构造不丢掉方言/能力检查；nullable 不改变原 SQL 节点身份。比较检查同 kind 和完整 codec，原生另核对 descriptor；共同 text 长度、DECIMAL 精度不同仍可比较。审查补齐 NULLIF 内部等号，且不会把转换后 BOOLEAN/text 的能力错误继承成原生值能力。已有节点的固有存储不能通过换 reader 或泛型声明变成另一个物理类型。

PostgreSQL 真实 UUID 验证业务 ID 映射、大小写规范化、INSERT RETURNING、带参数筛选、LEFT JOIN、CTE/page、UNION 去重、LAG 经 CTE 的 NULL、COALESCE、文本 CAST 及更新。MySQL 真实证明非 NULL 非法日期 CAST 可变 NULL；另外保留了零日期参数与字面量在 NO_ZERO_DATE 下不同的结果。共同 nativeCast 因此统一推导 T|null，参数绑定仍先经过目标 codec 校验。

79 个单元用例与类型负例通过，包含缺失descriptor、外部配置变化、不同identity/codec、错误方言、相等/排序/分组/窗口分区能力，以及文本操作不可用于UUID。三库已有报表、写入、窗口和事务契约也复核通过；新的完整矩阵交本候选自己的 CI。测试创建的随机表和自有连接池按 finally 清理。

## 2026-10-21：共同日期与本地时间

30d5f4e1671777f1a438e2ddd0af66af84a0cf65 的 [TS CI 39494263596](https://github.com/kenconnet666/easy_query_pro/actions/runs/39494263596) 与 [Java/三库 CI 39494263875](https://github.com/kenconnet666/easy_query_pro/actions/runs/39494263875) 均成功。

新类型保持原生 DATE/TIMESTAMP 投影和严格字符串 codec。三库验证 1000/9999 年边界、闰日、夏令时跳过/重复字段、微秒、NULL、INSERT/UPDATE、原生日期 CAST、MIN/MAX、LAG、CTE/UNION、带参数筛选，进程 TZ 在 UTC/New York/Shanghai 三种设置下结果不变。PG SET LOCAL DateStyle='SQL, DMY' 后明确拒绝非 ISO 结果，事务归还后恢复，不猜测格式或自动改写为 TO_CHAR。

HANA 首次实测返回 9 位小数秒，旧 7 位 parser 红测失败；读取改为接受额外零位后通过，非零第七位仍通过真实表回读明确拒绝。第二个独立池启用 isoTimestampOutput:true，确认 T 分隔同样无损。两个池均设置 emptyTimestampIsNull:false；服务器常量 CAST('' AS DATE/TIMESTAMP) 证明 EMPTY 非 SQL NULL，驱动保留非 NULL 值，nullable codec 仍拒绝域外值，未与真正 SQL NULL 混淆。

82 个单元用例、类型正负例、构建、格式检查和三库契约通过。测试表和自有池在 finally 清理，持久数据库未修改配置。共同年份、ISO DateStyle、HANA EMPTY 和超微秒边界均记录在 temporal.md；更宽原生时间类型继续留在 T08，不以隐式转换冒充共同支持。

## 2026-10-21：实际绑定参数预算

三库共同默认 65,535，只允许下调。PG/MySQL 硬限制有官方协议与源码依据；HANA 明确是当前项目策略，没有把未验证的 Node 总参数极限写成数据库事实。

在 renderer 的实际 append 入口检查，覆盖 CTE、HANA 布尔投影、MySQL NULLS 排序和重复参数扩展。INSERT 原组超限才沿连续区间二分，固定 RETURNING 参数也参与每条预算；单行超限让整份计划在 acquire 前失败。其他查询不自动拆分。

88 个单元与类型用例通过，含真实构造 65,535 绑定成功／65,536 绑定提前拒绝，后部大行失败时 acquire=0、变量绑定次数、编号复位、固定RETURNING开销及不泄露参数值。三库低预算真实写入验证五行按序落库与事务提交，PG每条RETURNING额外参数、MySQL多行拆分和HANA单行路径均通过；测试表finally删除。报文字节限制仍由驱动/服务器和业务codec处理，未做不可靠大小估算。

## 2026-10-21：清洁 tarball 消费者

ca8e50c7a1759a4e974dc93c853c8a1b4fc8c39c 的 [TS CI 39497347207](https://github.com/kenconnet666/easy_query_pro/actions/runs/39497347207) 成功；其 Java CI 被后续提交取消。继承其代码的公共映射修复 731cc9561355218c1c277d35780cdaace34c2b33 的 [Java/三库 CI 39498305188](https://github.com/kenconnet666/easy_query_pro/actions/runs/39498305188) 成功，包含已增加的独立 Java 消费者与 HANA Node 检查。

新增 pnpm test:consumer，在独立临时目录安装真实 tarball；关闭 peer 自动安装、安装脚本和外部 pnpm hooks。检查核心及三驱动导出、十次 LEFT JOIN／四层 CTE、类型负例和运行时 SQL/decoder。编译器实际文件清单只允许消费者、编译器标准库与安装包 dist，防止开发源码或环境类型依赖掩盖问题。

修正打包清单，包含 declaration map 引用的 src 源文件；仍从 dist 声明和运行时代码消费。遍历所有声明 map 验证目标存在且位于同包。首次工具运行分别遇到 TS 7 未开放 bin 子路径和 ESM-only 包不能用 require.resolve 解析，改用 package.json bin 元数据与真正 import.meta.resolve；没有通过开放内部模块或添加 CommonJS 导出规避问题。TS 7 标准库来自锁定平台包，文件边界也单独核对。

本地清洁消费者成功，无数据库 peer/@types/node/@types/pg；观测 81 个编译文件、16,973 次类型实例化、约 17 MiB 编译器内存。临时安装/压缩包/输出在 finally 核对路径后清理，共享缓存保留。四个 Node/OS CI 任务追加同一流程与日志，真实数据库作业保持独立；候选不因本地单次成功就宣称新 CI 已通过。

## 2026-10-21：PG 数组与连接内状态检查

打包消费者提交 d8cdc3131211d30f889fb9147b28a7715cd485af 的 [TS CI 39499980312](https://github.com/kenconnet666/easy_query_pro/actions/runs/39499980312) 与 [Java/三库 CI 39499980377](https://github.com/kenconnet666/easy_query_pro/actions/runs/39499980377) 均成功。随后 HANA 兼容测试提交 1d9356972a5039f4915f29c5bbfb90746a8cd116 的 [CI 39503146808](https://github.com/kenconnet666/easy_query_pro/actions/runs/39503146808) 成功；这些均是前序证据，当前数组候选另交 CI。

一维数组通过原生 DataType 与 mount 接入，复用元素完整 codec。新增 postgres-array 3.0.4 负责 wire 反转义，入口先检查结构防止畸形引号输入导致第三方解析器回退；没有再实现一套解码器。审查发现 JS Unicode 空白范围比 PG scanner_isspace 更宽，先加入 NBSP/EM SPACE/BOM 红测，再限制为六种 ASCII 空白，实际 PG round-trip 通过。

94 个单元用例与类型正负例通过，覆盖 NULL、空洞、维度、业务映射、错误方言、参数顺序和整体数组 map 拒绝挂载。PG 实库覆盖 BIGINT 9007199254740993、精确金额、微秒、UUID 对象、特殊文本、SQL NULL 与元素 NULL、CTE/UNION、array_nulls off 拒绝后切回 on、65,536 元素仅一个绑定。状态检查与目标 SQL 同队列执行，定向并发单元证明 SET 不会插进检查与使用之间，release 等待已提交操作。

新依赖加入后，真实 tarball 离线消费者再次通过，未自动安装任何数据库 peer；观测 82 文件、16,127 类型实例化、约 17 MiB 编译器内存。当前改动只涉及 PG，MySQL/HANA 的全面运行交并行 CI，不以无关重复本地运行占用时间。随机 PG 测试表和打包临时目录按 finally 清理，持久数据库和共享缓存保留。

## 2026-10-21：JSON 空值与原始数值文本

先以旧实现完成三个红测：JSON null 与 SQL NULL 相等、stringify 静默丢弃/改变值、接受已预解析的 driver 对象。修复位于 schema codec，三库驱动仍使用既有局部文本读取。jsonNull 只表示顶层 JSON null，nested null 不变，validator 不会被绕过；原文 jsonText 语法校验后返回原字符串，不使用校验时的 JS number 重序列化。

只读审查补到 NonNullable<T>：Exclude<unknown,null> 无法排除 null，原未知验证器会出现静态允许而运行时拒绝。新增 unknown 的 null/undefined 类型负例，声明文件消费者也覆盖此边界。文档明确普通 JSON 小数仍按 JS number 舍入，原文 codec 也不承诺改变服务器本身的存储精度。

103 个单元测试、类型正负例和三库实库通过。真实 JSONB/JSON/NCLOB 列验证 jsonNull/null/字符串null 的分离、嵌套中文与引号、9007199254740993 原始文本、错误读取为 JS 文档时拒绝、LEFT JOIN、CTE分页、JSON null 与 SQL NULL 的更新。MySQL2 在一个已显式指定 utf8 的 JSON 元数据路径输出编码建议，但中文/emoji与完整结果断言通过；未修改依赖源码或掩盖日志。

数组提交 2e7d66b6bacb877ede05d443d8c68766a661df79 的 [TS CI 39506917747](https://github.com/kenconnet666/easy_query_pro/actions/runs/39506917747) 中 Linux 与数据库通过，Windows 离线消费者因不同磁盘选择不同 store 失败。独立修复 3258a31b235cfeae7738ea5e11ec59946fc2e523 显式选择并核对工作区缓存，保持 offline；本地真实 tarball 消费通过，新矩阵异步验证。此失败不被上一批绿色状态替代。

本批随机 JSON 表与自有池按 finally 释放，包验证临时目录核对后清理，既有持久实例保留。

提交前复查，3258a31b235cfeae7738ea5e11ec59946fc2e523 的 [TS CI 39507936184](https://github.com/kenconnet666/easy_query_pro/actions/runs/39507936184) 六任务全部成功，Windows 跨盘修复获得对应 SHA 的验证。本批新增 sentinel 声明和运行时断言后，清洁消费者再次通过（83 文件、15,586 类型实例化、约 17 MiB）；JSON 候选仍需自己的完整矩阵。
