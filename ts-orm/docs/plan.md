# TypeScript ORM 持续执行计划

2026-10-21 用户授权主线程在本仓库新增 TS ORM，并将 Java EasyQuery 的原完整蓝图交给子代理继续实施。两条工作线并行，主线程串行处理共享仓库提交；不改变 Java 原计划的验收要求。

## 目标与取舍

构建可扩展、灵活、类型与运行时行为一致的 ORM，以 EasyQuery 3.2.17 为主参考。目标数据库沿用 PostgreSQL、MySQL、HANA。先贯通真实查询与事务，再沿同一架构丰富关系查询、报表和写入，不以演示原型替代可用目标。

不要求用户维护重复 entity interface、装饰器或生成 client。声明式表定义推导 Row/Insert/Update；optional、nullable、default/generated 分开。保留精确数值和自定义 codec，类型断言不冒充运行时转换。

Query/Expr/关系引用不可变。表达式带来源身份，编译时核对作用域；派生表和 CTE 冻结输出列与 decoder。LEFT JOIN 只改变当前关系的可空性，不修改 schema。不做无证据谓词下推或 count 优化，不复制 Java 可变 Proxy/共享上下文。

保持一个包，按职责分目录。可选驱动使用独立子路径，核心不依赖某个 driver。初始不加入 identity map、脏跟踪、自动关系发现、全局 module augmentation、完整自动迁移或分片合并；这些不作为查询与报表的前置成本，需有具体需求与证明才扩展。

## 目录与职责

| 目录                                    | 职责                                                |
| --------------------------------------- | --------------------------------------------------- |
| src/schema                              | 类型、codec、列和表定义，推导读写类型               |
| src/expression                          | 不可变表达式、参数、谓词、函数、聚合及扩展挂载      |
| src/query                               | 关系、作用域、投影、JOIN、派生表、CTE、UNION 和写入 |
| src/compiler                            | 语义校验，SQL/绑定/结果计划的一次编译               |
| src/dialects                            | 三库 SQL 能力与渲染差异                             |
| src/execution                           | driver、结果映射、事务和资源所有权                  |
| src/drivers                             | PostgreSQL/MySQL/HANA 可选驱动适配                  |
| test/unit、test/types、test/integration | 行为、类型正负例与真实数据库验证                    |

## 工作单元

| ID  | 内容                               | 验收                                            | 状态                                                    |
| --- | ---------------------------------- | ----------------------------------------------- | ------------------------------------------------------- |
| T00 | 固定架构、参考版本、工具与独立工程 | 文档、锁定依赖、与 Maven 隔离                   | 已建立，完整验收继续                                    |
| T01 | schema/codec/表达式/作用域         | 无生成器推导、NULL与省略、参数类型、错来源拒绝  | 基础与写入metadata类型通过，更多类型继续                |
| T02 | SELECT/JOIN/显式投影与不可变分叉   | 同源自连接、LEFT JOIN、字段重排与decoder        | 基础通过两库实测，更多JOIN/嵌套投影继续                 |
| T03 | 三库编译与驱动                     | 参数顺序、标识符引用、真实结果、连接释放        | 三库真实查询/事务通过，继续扩充复杂结果类型             |
| T04 | 派生表/CTE/UNION与count/page       | 按输出位置映射、codec兼容、真实结果行数         | 派生/非递归CTE/UNION/count/page通过三库，并发快照已验证 |
| T05 | INSERT/UPDATE/DELETE和事务         | 写入类型、默认/生成列、回滚、失效tx、失败释放   | 三库CRUD/隔离选项及PG RETURNING通过，HANA池RO明确拒绝   |
| T06 | 泛型namespace与表达式factory       | 扩展隔离、方法链类型保留、明确支持方言          | namespace/mount及结构化renderer已验证，完整组合继续     |
| T07 | 分组/HAVING/条件聚合/窗口/报表     | NULL、空集、聚合层、窗口边界、分页              | 分组/ROWS/排名/前后值三库通过，AVG/RANGE/分组集继续     |
| T08 | 方言专属类型/函数/来源             | 选取有实际增量的PG/MySQL/HANA能力，不复制共同层 | 开放类型协议/PG UUID/共同日期时间通过；PG数组、JSON继续 |
| T09 | 关系加载、批量与写入可靠性         | 避免N+1、事务边界、部分失败、参数/SQL大小限制   | 顺序批次与失败上下文已验证，关系/大小边界继续           |
| T10 | CI并行矩阵、干净消费者与类型性能   | 10表/多CTE、声明消费、pack后运行、三库CI        | 基础六任务CI通过，HANA复用Java实例；消费验证继续        |
| T11 | 文档示例、性能/资源审查及成熟验收  | 当前SHA的完整证据与明确限制                     | 待实施                                                  |

每一批本地只运行必要的聚焦检查。完整测试交 CI，不等待新运行；提交前检查前一提交状态。共享持久数据库不清理，只删除本任务拥有的测试对象。凭据只从环境读取。

## 已核对的参考

EasyQuery 源码基线为相邻仓库 3.2.17（2b25e9510868b4f9071365510393b0b02a451cda）。保留结构化表达式、metadata、方言、参数与连接所有权，避免直接复制 Java APT、Draft 数量族和 ThreadLocal。

辅助参考以官方文档和源码为准：

- [Kysely 扩展](https://kysely.dev/docs/recipes/extending-kysely)：小型表达式协议与组合函数。
- [Kysely 数据类型](https://kysely.dev/docs/recipes/data-types)：TS 类型不能替代 driver 解码。
- [Drizzle SQL/mapWith](https://orm.drizzle.team/docs/sql)：显式 decoder 与结构化 SQL。
- [Drizzle JOIN](https://orm.drizzle.team/docs/joins)：外连接的NULL传播与嵌套对象存在性边界。
- [MikroORM defineEntity](https://mikro-orm.io/docs/define-entity)：无装饰器的运行时schema推导。
- [MikroORM identity map](https://mikro-orm.io/docs/identity-map)：有状态实体追踪的隔离成本。

研究源码版本标识为 Kysely 0.29.7、Drizzle 0.45.4、MikroORM 7.2.0，不将其混为生产依赖或已验证功能。
