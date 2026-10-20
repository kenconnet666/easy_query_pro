# EasyQuery TypeScript ORM

在 EasyQuery Pro 仓库内独立构建的 TypeScript ORM。以 EasyQuery 3.2.17 的表达式、映射、查询组合和扩展机制为主要参考，结合 TypeScript 的映射类型、类型推导和函数组合，使用显式 schema，无需注解或生成器。

当前处于实现阶段，完成标准和状态见 [执行计划](docs/plan.md)。Java 增强包由独立子代理继续推进；此目录使用自己的 Node/TypeScript 工程，避免影响 Maven 模块。

当前基础已通过类型正负例、行为用例以及 PostgreSQL/MySQL/HANA 的真实查询和事务验证；各批次数量、证据与尚未完成的能力见 [验证记录](docs/evidence.md)。仍在持续完善原生类型、关系加载与报表能力，当前包未发布。

设计边界：

- schema 是运行时元数据与静态类型的唯一来源。
- Query、关系引用与表达式是不可变值，同一基础查询可以安全分叉。
- SQL、按位置绑定的参数、投影解码计划由一次结构化编译产生。
- 公共能力优先，MySQL/PostgreSQL/HANA 差异由显式方言与驱动实现。
- client 扩展命名空间保留具体类型；业务表达式通过 factory 和普通函数组合挂载。
- 事务使用显式异步回调，在同一连接上执行并验证关闭状态。
- 不默认把 DECIMAL/BIGINT 变为 JS number，也不把无时区时间默认解释成 JS Date。

开发命令：

```sh
pnpm install
pnpm build
pnpm typecheck
pnpm test
```

仅建立文件或类型签名不算完成；每个可用能力都需要类型负例、运行时结果和相应数据库证据。

## 查询示例

```ts
import { Pool } from "pg";
import { column, createDatabase, postgresDialect, table, types } from "@easy-query-pro/orm";
import { postgresDriver } from "@easy-query-pro/orm/postgres";

const users = table("app_user", {
  id: column(types.integer()).primaryKey(),
  name: column(types.text(200)),
  managerId: column(types.integer()).nullable().named("manager_id"),
});
const pool = new Pool({ connectionString: process.env.DATABASE_URL });
const db = createDatabase({ dialect: postgresDialect, driver: postgresDriver(pool) });

const base = db.from({ user: users });
const report = base
  .leftJoin({ manager: users }, ({ user, manager }) => user.managerId.eq(manager.id))
  .where(({ user }) => user.id.gt(0))
  .select(({ user, manager }) => ({ id: user.id, name: user.name, manager: manager.name }));

const rows = await report.execute(); // manager: string | null
const total = await report.count(); // bigint，统计当前查询的实际输出行
await pool.end(); // 池属于应用，ORM 仅借用并归还连接
```

schema 声明不执行 DDL。查询需要明确 select；同一个 base 可以独立派生多个 where 分支。as() 暴露新的关系字段并保留 codec，外层表达式的业务子类方法需显式重新 mount。count 使用派生查询保留 DISTINCT/limit 等语义，目前不做删投影优化。

显式排序后的查询可调用 `page(页码, 大小)`，总数和总页数使用 bigint。COUNT 和数据共用一条连接；稳定视图使用 `db.transaction(callback, { isolationLevel: "repeatableRead" })`。详细能力与失败边界见 [分页与事务](docs/transactions.md)。

## CTE 与集合查询

```ts
const eligible = db
  .from({ u: users })
  .where(({ u }) => u.id.gt(0))
  .select(({ u }) => ({ id: u.id, name: u.name }))
  .asCte("eligible_users");
const first = db
  .from({ e: eligible })
  .where(({ e }) => e.id.lte(100))
  .select(({ e }) => ({ id: e.id, name: e.name }));
const second = db
  .from({ e: eligible })
  .where(({ e }) => e.id.gt(100))
  .select(({ e }) => ({ id: e.id, name: e.name }));
const combined = db
  .from({ result: first.unionAll(second) })
  .orderBy(({ result }) => result.id.asc())
  .select(({ result }) => ({ id: result.id, name: result.name }));
```

CTE 定义按依赖顺序在一个顶层 WITH 中生成；同一定义反复引用只声明一次，每次引用仍有独立别名。当前共同能力为非递归 CTE，同名不同定义、循环和遮蔽实体表会明确拒绝。

union/unionAll 产出一个新来源，后续查询通过 from 绑定结果列。两侧投影必须有相同字段和顺序、SQL 存储描述及完整 codec 映射链；可空性自动合并。分支的排序/分页与结果的排序/分页分别保留。HANA 的 JSON 当前按 NCLOB 存储，不能用于 UNION 去重。

显式 select 后可用 `.exists()` 创建存在性谓词，单字段投影还可 `.scalar()` 作为可空标量；支持祖先关系捕获和自动 SQL 别名避让。原聚合/DISTINCT/分页保持，标量多行保留数据库错误。CTE、分组、HANA 和扩展 renderer 的边界见 [复杂子查询](docs/subqueries.md)。

## 类型化写入

```ts
const entries = table("entry", {
  id: column(types.integer()).generated(),
  name: column(types.text(100)),
  note: column(types.text(100)).nullable(),
  attempts: column(types.integer()).defaulted(),
});
await db.transaction(async (tx) => {
  await tx
    .insert(entries)
    .values([{ name: "first" }, { name: "second", note: null }])
    .execute();
  await tx
    .update(entries)
    .set((entry) => ({ attempts: entry.attempts.add(1) }))
    .where((entry) => entry.name.eq("first"))
    .execute();
  await tx
    .deleteFrom(entries)
    .where((entry) => entry.name.eq("second"))
    .execute();
});
```

`generated()` 表示数据库生成的只读列，INSERT/UPDATE 都禁止赋值；允许显式覆写的 identity 或默认值使用 `defaulted()`。`nullable()` 与 `defaulted()` 允许 INSERT 省略，显式 undefined 也表示省略，null 只允许写入可空列。声明只描述已经存在的数据库表。

UPDATE/DELETE 需要 where，整表操作使用显式 allRows。SET 可以引用本列做运算；同时修改两列时，一个赋值不能读取另一个也被修改的列，以避免 MySQL 与 PostgreSQL 的求值顺序差异。

所有批次在获取连接之前完成计划校验。PG/MySQL 把连续相同列形状合为多行 VALUES，HANA 按单行语句顺序执行，每批借用同一连接。整批原子性由上例的 transaction 提供；自动提交模式可能保留失败前已完成的语句。WriteExecutionError 包含失败语句下标、已完成语句数及已经确认的影响行数，保留原始 cause。空输入数组返回 0 且不借连接。HANA 零显式列的 INSERT 在编译时拒绝。

每条语句默认最多 65,535 个实际绑定，可用 `dialect(name, { maxParameters })` 下调。超限 INSERT 保持输入顺序拆分，单行仍超限则整份计划在借连接前拒绝；SELECT/UPDATE/DELETE 不自动拆查询。参数与报文字节是不同限制，详见 [参数预算](docs/batch-limits.md)。

PostgreSQL 支持 `.returning(entry => ({ id: entry.id, name: entry.name }))`，结果为 `{ affectedRows, rows }`，沿用 SELECT 的精确 codec；MySQL/HANA 编译时拒绝此原生能力。返回行顺序没有与输入逐行配对的承诺。自动提交下，结果解码可能在写入完成后失败；需要一起成功时在事务回调内执行。

## 类型与扩展边界

分组、HAVING、条件聚合、CASE/COALESCE/NULLIF、ROWS 窗口和多层汇总的用法见 [报表查询](docs/reporting.md)。COUNT 与排名返回 bigint，MIN/MAX 和值窗口保留来源 codec 并允许 NULL，SUM 返回精确 decimal 字符串并检查共同值域。命名分组键在 select/having/orderBy 的第三个回调参数中保留具体类型。

INTEGER 限定有符号 32 位，BIGINT 使用 bigint，DECIMAL 使用精确字符串，precision 为 1–38，scale 为 0–min(precision,30)。参数和结果都校验该范围；有效数字之外的尾随零不改变值域。NULL 在业务 codec 之前处理；nullable().map() 的静态结果仍包含 null。undefined 不等于 SQL NULL。

`types.float64()` 用于有限双精度测量值，`aggregate.avgApprox()` 及总体/样本方差、标准差明确在近似域计算，复用条件/窗口/CTE。精确 SUM 不隐式接收浮点值；数值转换、DISTINCT 次序和 PG 输出精度前置条件见 [近似统计](docs/statistics.md)。

`types.date()` 与 `types.localDateTime()` 使用 ISO 字符串保留业务日期／本地钟表字段及微秒，不经过 JS Date 或时区转换。PG ISO DateStyle、HANA EMPTY 与额外小数位的约束见 [日期与本地时间](docs/temporal.md)。

`types.json(validate)` 保留运行时校验，顶层 JSON null 使用 `jsonNull`，SQL NULL 仍由 `.nullable()` 和 JS null 表示。`types.jsonText()` 读取原始 JSON 文本，避免 ORM 再经过 JS number 丢掉数值精度。序列化拒绝及数据库自身的规范化边界见 [JSON 值合同](docs/json.md)。

扩展通过 createDatabase 的 extensions 或 withExtensions 安装，namespace 具体类型在查询和事务中保留。mount(factory) 返回工厂的具体表达式类型。函数 renderer 返回 sqlFragment 结构节点，编译器按最终顺序绑定值；普通值先通过 valueOf(value, type)，标识符使用 identifier，不能把用户值放进静态 SQL 文本。

`postgresTypes.uuid()` 与应用的 namespaced SQL kind 共用 DataType/codec，不要求生成器或全局注册。原生字段经过外连接、CTE、UNION 和窗口后保留类型；方言和比较能力在编译时检查。定义方式和 CAST 的可空边界见 [原生类型](docs/native-types.md)。

`postgresTypes.array(element)` 支持一维 PG 数组与元素业务映射。显式 `.mount(pgArray)` 获得 contains/overlaps/anyEquals/cardinality；空数组、SQL NULL 和 NULL 元素分开处理，精确数值及时间不损失。维度和连接状态约束见 [PostgreSQL 数组](docs/postgres-arrays.md)。

原生函数返回 SQL 条件时声明 `syntax: "predicate"`，默认是标量值。HANA 的条件在 SELECT/ORDER BY 值位置会转换成保留 NULL 的 CASE；WHERE/JOIN 中的 BOOLEAN 标量则显式比较 TRUE。renderer 不需要自己拼接这类共同转换。

PostgreSQL 适配使用查询局部文本 parser，不改全局配置；binary 模式不在当前适配范围。MySQL 使用服务端预处理，并局部保留精确数值/JSON文本。HANA 使用 SAP 官方客户端的显式连接池，完整获取结果后归还连接；池重置隔离级别，但 READ ONLY 访问模式不会自动复原，当前适配器提前拒绝该选项。应用负责最终关闭池。驱动包为可选 peer，使用相应子路径时安装 pg（TS 工程还需 @types/pg）、mysql2 或 @sap/hana-client。

HANA 示例：使用 `hana.createPool({ serverNode, uid, pwd, emptyTimestampIsNull: false }, { poolCapacity: 4, maxConnectedOrPooled: 4 })` 创建应用池，通过 `hanaDriver(pool)` 与 `hanaDialect` 创建 client。凭据和传输加密策略由应用配置；生产连接应按部署要求配置 TLS。SAP 客户端包含原生组件，pnpm 安装时需允许该包的构建脚本，仓库已在 pnpm-workspace.yaml 明确授权。

## 本地与 CI

在此目录运行 `pwsh -File tools/test-local.ps1` 可读取已有 Debian Docker 测试实例的进程内凭据并运行三库契约；不打印密码或写入环境文件。可用 `-Database hana` 只验证改动相关的数据库。普通 pnpm test 不要求数据库。

`pnpm test:consumer` 验证真实 tarball 的离线安装、公开声明和运行时入口，包含十层 JOIN／四层 CTE、类型负例与源码导航目标检查。详见 [独立消费者](docs/package-consumer.md)。

独立 CI 将 Node 22/24 与 Windows/Linux 检查并行，MySQL/PostgreSQL 分别使用已有 Compose 的 2 GiB/1 GiB 限制。HANA Node 契约复用 Java CI 的 12 GiB HANA 作业与临时实例，不再启动第二个大容器。长测试交 CI；本地只做改动相关的必要验证。
