# Java EasyQuery Pro 兼容与迁移

当前基线为 EasyQuery 3.2.17、Java 17，目标数据库限定 MySQL、PostgreSQL 与 HANA 2.0 本地部署。下表描述已有实现和定向证据；一个 fixture 通过不等于对应蓝图 ID 的全部组合已关闭。最终验收继续绑定 [执行记录](execution.md) 中的具体 SHA、产物和强制 CI。

## 版本范围

| 组件 | 当前已实际使用的版本 | 范围 |
| --- | --- | --- |
| EasyQuery 与 APT | 3.2.17 | 所有上游模块和 processor 保持同一版本 |
| Java | JBR 17.0.14，release 17 | 不启用 preview；其他 JDK 主版本另做兼容验证 |
| Maven | Wrapper 3.9.16 | 工具链选择 JDK 17；不依赖系统默认 Java |
| MySQL / JDBC | 8.4.11 / Connector/J 9.7.0 | 当前 8.4 目标矩阵；不自动覆盖 5.7、其他主版本或兼容产品 |
| PostgreSQL / JDBC | 17.11 / pgJDBC 42.7.14 | 当前 17 目标矩阵 |
| HANA / JDBC | 2.00.088.00.1760424921 / ngdbc 2.30.11 | HXE 租户验证；HANA Cloud 和其他 revision 不自动继承结果 |
| Spring 示例 | Boot 3.5.16 / Framework 6.2.19 | 原 starter 3.2.17 + 显式安装 helper，共享 Spring 事务 |
| 测试连接池 | HikariCP 7.0.2 | 只用于示例/测试，应用自行选择 DataSource |

## 能力与证据入口

“已有验收”只针对列明的契约；仍开放的项目保留在同一行或后续边界清单中。

| 蓝图 ID | 当前实现与验收 | 说明入口 |
| --- | --- | --- |
| H01 | 离线安装、方言身份、错库、多 client 与真实 HXE 连接已有验收 | [开发环境](development.md)、[架构](architecture.md) |
| H02 | JOIN、相关子查询、UNION、普通 CTE、clone、count/page 等已有真实组合；任意递归 CTE 明确拒绝 | [函数与查询](hana-functions.md) |
| H03 | 常规/批量/选列/表达式更新及逻辑删除协作已有验收；零显式列插入仍开放 | [写入](hana-writes.md) |
| H04 | 应用 ID、identity、sequence、回调、失败回滚与短并发键归属已有验收 | [生成键](hana-generated-keys.md) |
| H05 | 原 `onConflictThen` 经原子 MERGE，对主键/明确唯一键和指定更新列验收 | [冲突写入](hana-writes.md) |
| H06 | 提交、回滚、两连接锁竞争和连接状态恢复已有验收 | [写入与事务](hana-writes.md)、HanaTransactionIT |
| H07 | 数值、Unicode、LOB、时间和转换器已有纵向验收；完整角色矩阵继续审计 | [类型](hana-types.md)、[LOB](hana-lob-dsl.md)、[字符](hana-characters.md) |
| H08 | 67 个公开函数工厂已有逐项审计；直接 distinct 字符串聚合仍开放 | [函数审计](hana-function-audit.md) |
| H09 | 导航、过滤器、逻辑删除、乐观锁、DTO/reader 和代表性多数据源已有验收 | HanaBusinessIT、[投影](dsl-projections.md)、[分片](dsl-sharding.md) |
| H10 | 目录、创建、预览、幂等、受控增量和权限已有验收；任意依赖对象迁移不泛化 | [迁移](hana-migrations.md) |
| H11 | 真实 HDI 标量/多值计算视图、标量表函数及独立运行时 SELECT/EXECUTE 已验收 | [HDI](hana-hdi.md)、[查询来源](hana-query-sources.md) |
| H12 | 原生树/森林、根/深度/顺序、循环/孤儿、祖先/后代和选定事实汇总；物理表 SELECT/撤权已验收 | [层级](hana-hierarchy.md) |
| D01 | 通用 DECIMAL、有界 Unicode 文本、typed value/NULL、DDL/CAST 角色与编译负例 | [精确类型](dsl-types.md) |
| D02 | 三库原生数值、unsigned、时长/interval、受限字符/LOB 等已有验收；全目录角色矩阵继续收敛 | [原生数值](native-numeric.md)、[完整设计](dsl-design.md) |
| D03 | 通用 JSON path 与三库选定读/谓词，MySQL JSON/PG JSONB 路径修改删除已有验收 | [通用 JSON](dsl-json.md)、[MySQL](mysql-json.md)、[PG](pgsql-json.md) |
| D04 | PG 一维数组绑定/读取、ANY、包含/重叠/元素与长度、NULL 和资源边界已有验收 | [PG 数组](pgsql-arrays.md) |
| D05 | 三库各自的真实全文索引、输入模式、匹配/评分和查询组合已有验收 | [HANA](hana-fulltext.md)、[MySQL](mysql-fulltext.md)、[PG](pgsql-fulltext.md) |
| C01 | 共同 ROWS、MySQL/PG 选定 RANGE、全局排名薄桥；HANA RANGE 明确拒绝 | [窗口](dsl-windows.md) |
| C02 | 参数、alias、子查询、克隆、错误和结果类型的代表性组合；不宣称任意位置笛卡尔积均已覆盖 | [投影](dsl-projections.md)、[扩展](dsl-extensions.md) |
| C03 | NULL-safe、NULLIF、数据库日期截断已有三库验收，历史日期定义域保持显式 | [日期截断](dsl-temporal.md)、[通用 DSL](dsl-design.md) |
| C04 | facade/表达式工厂、SELF、窗口规格、局部扩展与 clone 有编译及真实消费者证据 | [扩展入口](dsl-extensions.md) |
| A01 | 订单条件指标、去重客户、加权均价/零分母、份额、全局/部门 Top N、13 月同比环比 | [订单](reporting-orders.md)、[时间序列](reporting-time-series.md) |
| A02 | 共同前缀 ROLLUP、PG/HANA GROUPING SETS、真实 NULL/小计 NULL、count/page | [汇总](dsl-groups.md) |
| A03 | 四项总体/样本统计、NULL/单行、FILTER/OVER；统一近似 Double，拒绝未证明的跨分片合并 | [统计](dsl-statistics.md) |
| A04 | 日历补桶与 PG LocalDateTime 固定微秒 date_bin；自然日与固定时长分开 | [时间序列](reporting-time-series.md) |
| I01 | 独立 Java/Spring JAR/POM/APT 消费者及三库 CRUD/共享事务、启动失败清理已有验收 | [Java 示例](../examples/java-client/README.md)、[Spring 示例](../examples/spring-client/README.md) |
| Q01 | 流/reader 异常、2049 行、4 worker/2连接、SQL/句柄计数与3预热/7次重复读取基线已有验收；复杂负载与长期压力继续收敛 | [查询资源](query-resources.md) |
| R01 | 二进制/源码/Javadoc classifier 与独立解析验收已建立；逐 ID 最终审计和最新候选 CI 继续推进 | [产物](artifacts.md)、[计划](plan.md) |

## 必须保留的边界

- H03 的零显式列插入尚未完成。省略部分普通列、应用明确传 NULL 或 sequence 生成一个实际 SQL 列，均不能冒充全默认空行。HANA 88 已有崩溃证据的 identity/default 语句不在共享实例或默认 CI 重跑。
- H08 的直接 `joining(..., distinct=true)` 仍拒绝。已有单列投影→DISTINCT→CTE→普通 joining 组合是显式方案，不是该标志已支持。
- 类型的 storage、cast、view、绑定和读取角色分别验证。原生 CAST 的舍入/截断不作跨库统一承诺；Java 表达式视图不是 SQL CAST。HANA 历法缺口、TIME 整秒与 LocalDateTime 的 100ns 精度约束保持。共同日期截断的已验收现代日期范围不能推广到任意历史列值；PG date_bin 当前仅 LocalDateTime 固定微秒桶。
- 上游无其他操作的右侧 JOIN 查询可能丢动态 schema/table，需真实投影保留来源。克隆仍遵守上游代理生命周期；并发使用独立 fresh query，不共享可变 Queryable。窗口、统计、全文评分和不安全聚合后计算不能套用逐行分片合并。
- Code First 只执行已建模的受控变更。HANA 88 带双引号列名的 ALTER 定义当前提前拒绝，不用重建表或改名隐藏差异；复杂依赖对象迁移继续验收。
- HDI 实库多值模型为 NVARCHAR/INTEGER，其他模型精度和参数类型仍需对应模型。列表 NULL/空列表不开放；HANA 多 SCORE 子查询 JOIN 与 MySQL 全文事务可见性按各自文档保留。
- 资源报告已包含有界的预热后重复读取分布；不同入口包含的查询构造、映射和服务器 CAST 工作不同，不能由差值声称纯框架开销，也不替代复杂负载与长期压力结论。
- 一个旧 SHA 的绿色、另一个 SHA 的 pending/cancelled 或可选任务 skipped，均不能替代最终候选全部强制检查。

## 安装与迁移顺序

1. 把 Java 工具链和应用运行基线切到 17，统一 EasyQuery 模块与 APT 为 3.2.17。按需选一个方言 DSL 模块，或只使用 HANA 底层适配；驱动、DataSource 与 Spring 由应用显式选择。
2. 构建 client 时先安装数据库配置与匹配的 Pro 扩展。在首次实体 metadata 加载前安装 handler/converter；默认映射只补缺项，应用同类 converter 实例保留。全文需要 MySQL/PG 的显式 `configureQueryScope`，后置自定义工厂须继承相应 Pro 工厂。
3. 原查询/写入 API 继续使用。只有精确类型、窗口规格或原生能力需要显式 facade；不要把每个 Queryable 都改成数据库泛型。`value` 的 Java 无损预校验、`nativeCast` 的数据库转换、`view` 的操作视图分别选择。
4. 从原始字符串 JSON/数组/interval 迁移时使用对应输入与值类型，保留 SQL NULL、JSON null、空数组/空文本的区别；不要用字符串拼接代替参数绑定或把整个列 converter 套到数组元素、排名等派生结果上。
5. 报表先过滤事实、聚合，再做窗口与展示筛选。未携带 Pro 窗口标记的上游模板入口用明确 CTE 固定计算层；分组计数、DISTINCT 窗口 count 和分页沿文档中的边界组合。
6. 保留原事务所有权。HANA 回填键在回滚后不会自动清空 Java 对象；批量标记不等于事务原子性。Spring 使用已有接入 helper 与共享事务验证，不另开隐藏连接。
7. 升级依赖或数据库 revision 时先保留当前候选产物与环境，再跑离线/编译消费者和相关实库 fixture，最后交完整 CI。生成 SQL 相同不足以代替 driver、NULL、精度、权限和资源生命周期回归。

诊断时保留最内层数据库错误码与 SQLState，以及 suppressed 清理异常。HANA 258 表示当前身份权限不足，不能转成空结果；HANA 7 的不支持组合、输入校验拒绝和未安装扩展错误分别处理。示例与测试凭据通过进程环境传递，不写入发行 POM 或文档。
