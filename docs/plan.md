# EasyQuery Pro 完整执行计划

范围与完成标准只由 [完整交付蓝图](blueprint.md) 定义。本表用于后续无人值守执行和恢复，不能通过删除任务或改变状态缩小蓝图的必交付范围。

当前状态：2026-10-19 用户已确认完整范围并授权进入目标模式无人值守实施。按依赖推进到 G7 全部通过；本地必要验证后提交 CI 并继续编码，下次提交前检查、修复上一轮 CI。阶段证据与未关闭问题见 [执行记录](execution.md)。

## 已完成的基础

| 项目 | 证据 |
| --- | --- |
| 分包、依赖、Maven Wrapper 和 JDK 17 | 工程基线 `7b9d96089327f035a9ee9baa542fd74b2fe75ea5` |
| IDEA MCP、APT 和独立重建 | 已清理生成文件后通过 IDEA 重建，使用指定 JBR 17 |
| MySQL/PostgreSQL 基础契约 | CI 中真实数据库执行 CRUD、绑定和事务；配置内存为 2 GiB/1 GiB |
| HANA Docker 和原生 JDBC | 12 GiB/4 CPU，认证 SQL 健康检查，真实版本 2.00.088.00.1760424921；参数与事务检查通过 |
| Windows/Linux 和三库基础 CI | [基线运行 39255071676](https://github.com/kenconnet666/easy_query_pro/actions/runs/39255071676) 通过 |

这些证据不代表 HANA EasyQuery 适配器、类型 DSL 或高级功能已经完成。

## 工作单元

| 工作单元 | 交付内容 | 前置 | 状态 |
| --- | --- | --- | --- |
| G0.1 | 将蓝图范围固定为执行目标，核对当前 HEAD、用户改动、环境和 CI | 范围确认 | 通过 |
| G0.2 | 建立完整 SQL/函数/类型/业务机制能力清单，映射到测试 | G0.1 | 实施中 |
| G0.3 | 验证 HANA generated keys/批量键、类型 DSL 互操作、真实计算视图部署等高风险路径 | G0.2 | 三条原型路径已有 CI；业务值对象/转换器路径已本地验证，精确类型与编译负例继续收敛 |
| G0.4 | 固定必需接口、版本限制、不可等价能力和阻塞解决路线 | G0.3 | 待实施 |
| G1.1 | HANA 配置、方言身份、SQLKeyword 与基本 ExpressionFactory | G0.4 | 原型已实现，待 G0 门槛与完整诊断收敛 |
| G1.2 | 普通查询、插入、更新、删除与参数绑定的纵向实现 | G1.1 | HXE 纵向测试通过，继续扩展组合覆盖 |
| G1.3 | identity、sequence、批量键、事务和基础类型的真实执行 | G1.2 | 回调时序已修复；短并发/池复用/失败回滚/触发器保护通过，长期与完整类型继续验收 |
| G2.1 | 完整选定查询组合：JOIN、子查询、UNION、普通 CTE、分页/count、clone | G1.3 | 基础与高级来源组合已有真实证据；上游无操作右表 JOIN 丢动态 schema/table 的边界需显式投影，完整组合继续验收 |
| G2.2 | 字符串、数值、日期、NULL、聚合、JSON 和已有窗口函数的 HANA 翻译 | G2.1 | 基础函数、8 种窗口和组合已执行；去重字符串聚合与少量旧工厂继续实施 |
| G2.3 | 类型/Unicode/LOB/时间点边界及绑定、读取和转换器协作 | G2.2 | Unicode/Byte、时间、UUID/LOB、业务主键和转换器协作已有证据；长期组合继续实施 |
| G2.4 | 冲突写入、锁竞争、批量失败与连接状态恢复 | G1.3、G2.3 | MERGE、锁等待、连接恢复、关联更新/删除已有证据；其他锁变体与零显式列继续实施 |
| G2.5 | 导航、过滤、逻辑删除、乐观锁、DTO、拦截器、多 client/数据源兼容 | G2.1–G2.4 | 导航写入/include、过滤、版本、DTO、两 client 已验证；多数据源/分片组合继续实施 |
| G3.1 | HANA schema/表/视图/列/键/索引/注释等元数据 | G2.3 | 目录与权限负例本地通过，等待当前提交 CI 与完整类型收敛 |
| G3.2 | Code First 创建、受控增量、预览、幂等和失败后状态 | G3.1 | 创建/重命名/扩宽/默认值/回滚通过；依赖对象和复杂迁移继续验收 |
| G3.3 | 专用测试 schema 与非 SYSTEM 应用用户验证 | G3.2 | 独立 schema 和非管理员迁移/权限检查通过，完整类型与业务集成继续覆盖 |
| G4.1 | 用三库真实消费示例定型通用/专属类型、CAST 与列视图 API | G0.4、G2.5 | DECIMAL 原型已通过三库真实消费，原生差异显式标注；其余类型/列视图继续收敛 |
| G4.2 | 通用类型参数、三库 DDL/CAST 分离、类型族重载、返回表达式静态类型 | G4.1 | DECIMAL、有界 Unicode 文本、typed value/NULL 和编译负例已落地；完整目录继续实施 |
| G4.3 | NULL-safe/NULLIF/数据库日期截断，以及 JSON path/布尔结果组合桥接 | G4.2 | NULL-safe、NULLIF、常规日期截断与结构化 JSON path/布尔桥接已实现；历史历法及完整组合继续验收 |
| G4.4 | 三库原生类型目录：unsigned、MySQL 时长、PG interval、HANA 受限文本等 | G4.2 | PG interval、MySQL unsigned/TIME、HANA LOB/JSON 文本及三库原生数值已有实库证据；字符及其余目录继续实施 |
| G4.5 | MySQL JSON、PG JSONB 与 PG SQL 数组的完整选定操作 | G4.2、G4.4 | MySQL JSON、PG JSON/JSONB 和一维数组选定操作已有实库证据；完整类型组合、分片及性能继续验收 |
| G4.6 | 编译负例、跨 client/错库负例、组合、类型传播与资源释放 | G4.3–G4.5 | 共同/原生逐行表达式、三库单/双 DataSource、Draft 分类、路由隔离和回滚已验证；派生关系及完整业务组合继续实施 |
| G4.7 | C04 泛型挂载、开放 DSL/默认 SPI、同族表达式重建与 SELF、clone 保留定制 | G4.6 | facade/表达式泛型挂载、共同能力继承、selector/关系工厂、CTE 克隆及 MySQL JSON/PG interval SELF 已验证；其余同族和窗口规格继续实施 |
| G5.1 | 共同 ROWS、无 partition 的全局窗口与 MySQL/PG RANGE；复用已有函数 | G4.6、G4.7 | 共同 ROWS/全局窗口已通过 CI；两库整数/精确小数及日期/微秒 RANGE 已通过实库和编译边界，待当前提交 CI 与完整报表组合 |
| G5.2 | 三库全文检索、索引 fixture、模式与评分作用域 | G4.6 | HANA 原子 CONTAINS/SCORE、真实同步索引、绑定阈值、查询组合及事务已本地验证；MySQL/PG 全文继续实施，当前提交完整 CI 待验收 |
| G5.3 | HANA 邻接树/森林、祖先/后代、深度、顺序、循环/孤儿策略 | G2.5、G4.6 | 构树、祖先/后代、有符号距离、重叠起点与事实汇总本地通过；当前导航提交 CI、完整类型/权限与业务组合继续验收 |
| G5.4 | HANA 参数化计算视图与表函数的真实 fixture、查询、绑定和组合 | G0.3、G4.6 | 标量与 NVARCHAR/INTEGER 多值计算视图、标量输入表函数已接入原 EntityQueryable；真实 HDI 验证默认值/NULL/命名绑定/CTE/JOIN/分页/事务，完整权限/模型精度/消费者继续验收 |
| G5.5 | A01 销售/订单报表：条件指标、加权均价、累计/占比、Top N、缺月同比/环比、分组分页 | G4.6、G5.1 | 分组→累计/移动均值/全局总额→外层 EQ/IN/分页已验证；条件指标、Top N 与完整时间序列继续实施 |
| G5.6 | A02 前缀 ROLLUP 与 PG/HANA GROUPING SETS、汇总标识；先验证公开分组 SPI | G4.6、G5.5 | 已实现并通过三库小计 NULL、空输入、HAVING、SMART 筛选、JOIN、参数键、CTE/count/page；当前提交完整 CI 异步验证 |
| G5.7 | A03 总体/样本方差及标准差、返回精度与分片拒绝 | G4.6、G5.5 | 四个入口、FILTER/窗口、空值/精度/JDBC 类型及嵌套拒绝已本地通过；等待当前提交 CI 并继续完整报表组合 |
| G5.8 | A04 日历补桶示例、PG date_bin；HANA 层级事实汇总 | G5.3、G5.5 | HANA 事实汇总、PG 固定微秒桶/负向边界/DST，以及三库补月→LAG→CTE/分页已本地通过；当前提交完整 CI 与时区时间点等独立边界继续验收 |
| G6.1 | 独立 Java 与 Spring 接入示例，验证安装顺序和选项生命周期 | G3、G4、G5 | 待实施 |
| G6.2 | 三库真实组合、并发、非管理员、失败路径与长结果集回归 | G6.1 | 待实施 |
| G6.3 | 可复现性能/资源基准及报告，检查额外 SQL 与泄漏 | G6.2 | 待实施 |
| G6.4 | 发行 JAR/POM/processor 的干净消费者验证 | G6.2 | 待实施 |
| G7.1 | 公共 API 文档、完整支持表、限制、迁移和诊断说明 | G6 | 待实施 |
| G7.2 | 对照每个必交付 ID 检查实现、测试、文档和未关闭问题 | G7.1 | 待实施 |
| G7.3 | 当前候选 commit 的全部强制 CI、产物和最终资源清理 | G7.2 | 待实施 |

`G3` 等阶段缩写表示该阶段全部工作单元通过。允许推进不依赖阻塞项的工作，但阶段门槛不会因此消失。

G4/G5 的具体 API 与方言职责以 [DSL 设计](dsl-design.md) 为准。通用新增 U01–U08、三库类型和查询级操作都要有对应验收，不将“工厂类已创建”作为完成证据。

2026-10-20 增补 A01–A04 的详细取舍见 [统计与报表蓝图](reporting-blueprint.md)。先修复投影读取生命周期，再推进窗口与报表契约；新增分析工作不会替代 G5.2 全文、G5.4 真实计算视图或 G6/G7 完整验收。2026-10-21 用户调整分工后，Java 方向由指定实现代理编辑与验证，其他研究代理保持只读；主线程串行提交和推送，共享 checkout 的提交批次交接期间停止编辑。

## 每项任务的证据记录

开始执行后，为工作单元维护以下记录，放在本文件对应条目或专门的执行记录中：

```text
Task: G2.4
Capabilities: H04, H05, H06
Status: planned | in_progress | awaiting_verification | passed | blocked
Decision: implementation choice and reason
Changed commit: full SHA
Local checks: exact command and outcome
CI evidence: run URL, tested SHA, required job conclusions
Remaining issues: reproducible failures or external blockers
Next action: one concrete continuation
```

任务状态不能仅由“代码已写完”决定。必需 CI 未结束、当前 SHA 尚未验证或外部条件缺失时，保留相应状态。引用基线 CI 只能说明基础状态，不能作为新功能的验收结果。

## 整体终点

以蓝图第 15 节为唯一完整完成条件，尤其包含：HANA 通过适配器执行、所有必交付高级能力、类型负例、非管理员与并发验证、独立产物消费、性能证据、完整文档和当前 commit 的全套 CI。

实施权限与暂停条件见蓝图第 16 节。发布、主分支合并和生产操作按实际授权处理，不把这些外部动作混同于代码与产物可用。
