# 统计与报表增强蓝图

2026-10-20 按用户强调的统计/报表需求增补。基线为 EasyQuery 3.2.17、JDK 17、MySQL 8.4、PostgreSQL 17 与 HANA 2.0 revision 88。这里的计划不是已实现声明；执行状态与当前提交的证据分别记录在 [计划](plan.md) 和 [执行记录](execution.md)。

## 能力分层与取舍

| 能力 | 决策 | 理由与边界 |
| --- | --- | --- |
| 条件 SUM/COUNT/AVG、去重、CASE、普通排名与 LAG/LEAD | 复用，新增报表验收 | 上游 `.filter(...)` 已通过 CASE 表达条件聚合；不新增 sumIf/countIf，也不把缺少原生 FILTER 语法误判为缺少能力 |
| 派生表/CTE/UNION 的读取器、返回类型、聚合分类 | 通用内部基础 | 复用 ColumnReader、selector 和 builder SPI；列重排、显式 valueConvert、DTO handler、聚合结果不可串用源列映射 |
| ROWS frame、空 partition、全局/分组窗口 | 通用增强，A01/C01 | 补上游窗口规格缺口，保留已有函数；窗口计算不能标为可跨分片逐行合并 |
| 数值/时间 RANGE | MySQL/PG 原生窗口 | HANA 当前基线明确拒绝；N 行和 N 天不是同一指标 |
| 前缀 ROLLUP 和汇总行标识 | 通用查询增强，A02 | 地区→门店→总计，单次数据库查询；通过公开分组 SPI 保留现有 GroupingN 结果，不复制类型族 |
| 指定 GROUPING SETS | PG/HANA 原生查询，A02 | 保留明确维度集合；MySQL 不伪造支持，不降级为多次查询后 Java 合并 |
| 总体/样本方差与标准差 | 通用近似统计，A03 | 输入在聚合前显式转 DOUBLE，结果为可空 Double；精确原生数值语义单列，不机械套用方言别名 |
| 15 分钟等固定间隔分桶 | PG date_bin，A04 | origin、步长与 timestamp 类型明确；已有年/月/周/日截断继续复用 |
| 缺失日期/月份补齐 | 共同日历表 + LEFT JOIN 示例，A04 | 不新建报表引擎；PG generate_series 先通过上游参数化 queryable 来源示例接入 |
| HANA 层级事实汇总 | 原生关系来源，H12/A01 | 先有正确树/森林，再补组织收入和科目余额；展示过滤与构造时剪枝分开 |
| 参数化计算视图/表函数参与统计 | HANA 来源，H11 | 复用已有真实 HDI fixture、queryable(sql, class, parameters) 和原映射/执行链 |
| MEDIAN/PERCENTILE_CONT/DISC | 原生候选，先验证，不先承诺统一 API | PG/HANA 的 ordered-set/window 形态不同，MySQL 无同等通用原语；API 定型前比较精度与空集规则 |
| CUBE、动态 PIVOT、任意 LATERAL/UNNEST、回归/傅里叶统计 | 暂不扩入必交付 | 当前场景可由明确分组集合、条件聚合和已有来源组合覆盖；控制指数级分组和执行协议扩张 |

## 具体报表验收矩阵

同一小型订单 fixture 含多个地区/部门、同日多行、退款、零数量、空部门、缺月、空金额和并列值。每个用例固定期望值，不能只断言 SQL 字符串或行数。

| 场景 | 必须验证 |
| --- | --- |
| 支付/退款计数、金额、去重客户数 | 条件不匹配、NULL、空集；沿用 CASE/filter，不增加重复调用入口 |
| 加权均价、部门占比 | 先确定数值类型再除法；NULLIF 分母；零/NULL 的结果不能被 primitive 默认值掩盖 |
| 每日累计、最近三条记录均值 | ROWS 起止、稳定次排序、边缘窗口；明确不代表最近三天 |
| 全局总额与分区总额 | 无 partition 和有 partition 的差别；ORDER BY 的默认 frame 不能误变为累计分母 |
| 部门 Top N 与并列名次 | RANK/DENSE_RANK/ROW_NUMBER 复用；窗口结果必须经外层查询过滤 |
| 月度环比与同比 | 缺月先补桶或按日期 JOIN；不能把稀疏数据上的 LAG(12) 宣传为同比 |
| 多级小计和总计 | 真实 NULL 与汇总 NULL 用 GROUPING 信息区分；HAVING、排序、空输入和分页/count |
| 分组→派生表→窗口→DTO | 返回类型与显式转换优先级；clone、别名重排、UNION 和分页结果稳定 |
| 总体/样本统计 | 空集、全 NULL、单样本、重复值、大数；精度/舍入和 JDBC 实际类型 |
| 层级事实汇总 | 根节点自身、空叶子、事实表一对多重复计数、显示深度不改变总额 |

分组结果的 count 与每组行数求和分别验收。报表自身的跨分片限制明确检查；没有合并语义证明的统计/窗口拒绝跨分片执行，不冒充 SUM/AVG 或普通逐行函数。

## 源码证据与实现顺序

### 开窗与复杂查询支持矩阵

下列组合属于正式验收范围，分别记录三库真实执行结果、SQL/参数和限制；尚未完成的项目保持计划状态。

| 查询组合 | 支持目标与验收 |
| --- | --- |
| ROW_NUMBER/RANK/DENSE_RANK、LAG/LEAD、FIRST/LAST/NTH_VALUE | 复用上游入口；排序/并列/边界/default/NULL，明确哪些函数受 frame 影响 |
| SUM/AVG/COUNT/MIN/MAX OVER | 分区和无分区、ROWS 起止、累计和整组窗口；输入与结果类型分别验证 |
| 多表 JOIN 与外连接 | ON 与 WHERE 的过滤差别、未匹配 NULL、同名列、跨 schema、避免重复计数 |
| 相关 EXISTS/NOT EXISTS、标量子查询 | 关联表归属、NULL 与空结果、参数绑定、嵌套与 clone |
| 多层普通 CTE、UNION/UNION ALL | 分支与输出位置映射、业务转换器兼容、重复消除、条件与参数顺序 |
| GROUP BY/HAVING → 派生表 → 开窗 → 外层筛选 | 分组统计后排名、占比和 Top N；不把窗口表达式直接放进不合法的 WHERE |
| 复杂查询的 count/page/order/clone | 稳定排序、总行数契约、重排后的 DTO/Draft 类型、重复执行无条件或参数污染 |
| ROLLUP/GROUPING SETS → 外层查询 | 汇总标识、真实 NULL、分组总数、筛选时是否保留总计 |
| HANA 参数化来源/层级来源 → JOIN/聚合/分页 | 复用同一 EntityQueryable、事务与映射，不建立独立原生执行通道 |

“复杂查询支持”不是把每个数据库的 SQL 全集声明成跨库能力。HANA 任意递归 WITH、当前基线 RANGE，以及未证明合并算法的跨分片窗口/统计保持明确边界；可用替代路径以原生层级或单数据库查询形式说明。

上游 `OverExpression`、`NextSelector` 和 `PartitionByTypeExpression` 只有 partition/order；`SumOverBuilder.partitionBy(...)` 要求非空。`Expression` 已有基础窗口函数，`ColumnNumberFunctionAvailable` 已有 SUM/AVG 和明确类型入口，`AbstractAggregateFilterExpression` 复用 CASE。这些是补规格而不复制函数的依据。

上游 `GroupSelector.columnFunc/sqlSegmentAs` 可承接受控分组结构，但 `GroupKeys/GroupingNProxy` 与多列 ROLLUP 的组合必须先做原型。禁止扫描/替换最终 SQL 字符串。若公开 SPI 无法保留查询契约，记录证据并调整设计，不能回退到私有字段修改。

`AbstractClientQueryable.asTreeCTECustom` 调用 CTERecursiveProvider 后仍固定构造 UNION 和递归 CTE，因此只替换 provider 不足以得到 HANA HIERARCHY。先提供显式 HANA 来源，复用原 EntityQueryable；已有树入口能否接入另行以公开 SPI 原型证明。

顺序为 G4.6 类型/聚合传播 → G5.1 窗口规格 → G5.5 完整报表 → G5.6 汇总与 G5.7 统计 → G5.8 时间序列和层级报表。日历补桶不依赖层级，可以提前验证；原有全文检索、参数化视图与最终产物验收继续推进。

HANA 多值计算视图输入按模型声明的列表协议编码为一个绑定参数，不当作 JDBC Array，也不把用户列表拼进 SQL。复用已部署真实计算视图的原型，再验证引号、逗号、中文、空列表、NULL、默认值和外层统计。

## 官方语义依据

### A02 的公开 SPI 与空输入边界

2026-10-20 核查：既有 GroupKeys/GroupingN 已保存强类型键及原表，公开 group builder 的结构化 segment 可承接 ROLLUP/GROUPING SETS。因此采用修饰原分组查询的方向，继续返回原 EntityQueryable，不复制分组代理。入口应同时完成显式投影，避免上游把分组片段作为默认 SELECT 时产生 `SELECT ROLLUP(...)`；count/page/clone 必须随原型一起验证。

只读三行 fixture 含真实 NULL，三库 ROLLUP 均返回六行；MySQL 8.4.11 支持 `GROUP BY ROLLUP(a,b)`，无需专门后缀。HANA GROUPING_ID 与 MySQL/PG GROUPING 的多键位掩码均为明细 0、末键小计 1、总计 3。PG/HANA 的 `GROUPING SETS ((a),(b),())` 返回五行。真实 NULL 不能用 isNull 当作汇总标识。

空输入有差异：MySQL/HANA 的 ROLLUP 返回零行，PG 保留一行空总计。共同入口已采用没有输入则返回空报表的契约，PG 通过受控 HAVING COUNT(*) > 0 对齐；原生 grouping sets 保留各库明确语义。增强入口现已通过三库绑定计算键、独立 JOIN 来源、分组总行数、分页和 CTE 测试。小计 NULL 经 Draft/DTO 保留各自契约；SMART 外层筛选不会下推到扩展分组之前。无跨分片合并证明时拒绝汇总执行，不套用普通 GROUP BY 合并器。完整 API 与上游 clone 自连接边界见 [分组汇总说明](dsl-groups.md)。

### A03 的原生精度核查

2026-10-20 只读 MCP 常量查询确认：MySQL 8.4.11、PG 17.11、HANA 2.00.088.00.1760424921 的 VAR_POP/VAR_SAMP/STDDEV_POP/STDDEV_SAMP 都忽略 NULL；空集/全 NULL 返回 NULL，单样本的总体结果为 0、样本结果为 NULL。此为原生语义探测，不是增强 DSL 或 CI 验收。

HANA 的 DECIMAL(19,2) 输入 1、2，STDDEV_SAMP 原生结果为 0.70；DECIMAL(19,4) 为 0.7071，DOUBLE 为约 0.7071067811865476。DECIMAL(19,4) 的 0.0001、0.0002，HANA 四个原生统计结果均为 0.0000。由此选择聚合前 DOUBLE 转换的共同近似契约；聚合后再 CAST 不能修复计算中已丢失的信息。

DOUBLE 也不能表示任意精确整数差值：相邻整数 9007199254740992、9007199254740993 转为 DOUBLE 后三库统计结果均为 0。文档和测试必须说明近似精度，并用相对/绝对误差判断常规浮点结果；不将金额等精确业务默认改成浮点存储。

四个入口复用上游 NumberFilterTypeExpression 的 filter/CASE 链。原生别名 STDDEV/VARIANCE 在不同数据库含义不一致，只用显式 POP/SAMP 语义。非有限值、超出 DOUBLE 范围、未证明算法的跨分片聚合不宣称跨库等价。官方返回类型依据为 [MySQL 聚合函数](https://dev.mysql.com/doc/refman/8.4/en/aggregate-functions.html)、[PG 统计聚合](https://www.postgresql.org/docs/17/functions-aggregate.html) 和下文 HANA 函数页；原生 MCP 探测不替代本包 JDBC 验收。

### 原生 RANGE 与固定步长分桶的后续约束

MySQL/PG 的 RANGE 规格沿用 WindowSpec 的 SELF/recreate 和 WindowFrame，不复制 partition/order。偏移边界保留 PRECEDING(0)/FOLLOWING(0) 类别；同类倒序偏移允许产生空 frame，不能套共同 ROWS 的位置比较。存在有限 offset 时只允许一个实际排序项；将 NULL 排序模拟为 CASE 加第二键会破坏这一条件。

PG 整数排序键不能一律绑定 BigDecimal offset，需保留整数/精确小数类型；时间 offset 复用非负 PgInterval.Finite 的月、日、微秒，不将月折算成 Duration。MySQL 时间 offset 是 `INTERVAL ? unit` 语法，与 SQL TIME/Duration 存储无关；数量绑定、单位受控。客户端/服务端 prepare 必须分别验证，原生常量 SELECT 不是 JDBC 参数验收。首批不引入 GROUPS/EXCLUDE、复合 interval、NaN/Infinity 或跨 DST 的 timestamptz 规则。

PG date_bin 确认是上游缺失能力，可表达固定 15 分钟与非零 origin。拟先使用 LocalDateTime、正且精确到微秒的 Duration、显式 origin，复用 PgInterval handler。只读查询已经确认 origin 之前的时间向下归桶；无时区 TIMESTAMP 不代表现实时间线上的固定自然日。上游 Timestamp.valueOf/setTimestamp 路径还需 JVM DST gap/overlap 的 JDBC 回归；若复现，优先在现有 PG handler 层用 JDBC 4.2 LocalDateTime 修正，不靠日期字符串绕行。

### 官方规则链接

ROWS 用物理行位置定义 frame，排名与偏移函数并非都受 frame 影响，见 [MySQL 8.4 frame 规则](https://dev.mysql.com/doc/refman/8.4/en/window-functions-frames.html) 和 [PostgreSQL 17 窗口调用](https://www.postgresql.org/docs/17/sql-expressions.html#SYNTAX-WINDOW-FUNCTIONS)。HANA 当前范围按 [2.0 SPS 08 SQL Reference 的窗口规范](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf#page=433) 核实，不把 Cloud 或其他 revision 自动视为已测试。

小计与分组集合参考 [MySQL ROLLUP/GROUPING](https://dev.mysql.com/doc/refman/8.4/en/group-by-modifiers.html)、[PostgreSQL grouping sets](https://www.postgresql.org/docs/17/queries-table-expressions.html#QUERIES-GROUPING-SETS) 与 [HANA GROUPING_ID](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/20e21cfd75191014a5cbbea031962073.html)。汇总标识的原生探测已完成，增强查询组合仍需验收。

固定间隔分桶参考 [PostgreSQL date_bin](https://www.postgresql.org/docs/17/functions-datetime.html#FUNCTIONS-DATETIME-BIN)：步长必须为正，不能含月或更大单位。自然日与固定 24 小时在时区/DST 环境下分别验证。

统计函数优先核对 [HANA VAR_SAMP](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/d1e36dfe43854bf5853fb7cca64b771b.html) 和 [STDDEV](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/c0c42b56360144e99bd443db90847597.html) 的单样本/返回类型，不能凭函数名认定别名等价。层级汇总参考 [HANA Hierarchy Developer Guide](https://help.sap.com/doc/e4fc5d26a38c4dc28802a3b87cd5eb10/2.0.08/en-US/SAP_HANA_Hierarchy_Developer_Guide_en.pdf#page=56)，多值输入参考 [HANA input parameters](https://help.sap.com/docs/SAP_HANA_PLATFORM/fc5ace7a367c434190a8047881f92ed8/d5c8230abb571014acc592c2c96d92bd.html?locale=en-US)。这些资料是设计依据，不能替代本项目当前提交的实库验收。
