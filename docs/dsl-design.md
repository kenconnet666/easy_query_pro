# 通用 DSL 核心与数据库方言设计

本文件细化 [完整交付蓝图](blueprint.md) 的 API 归属、类型目录和方言工作量。目标仍是完整成熟交付；下面的名称是规划接口，不是已经实现的 API。实现时可以调整命名与重载，但不得改变能力归属、语义或验收范围。

已经实现的 BIGINT、有限浮点参数、DECIMAL、有界 Unicode 文本与 typed NULL/原生 CAST 入口见 [类型表达式说明](dsl-types.md)。原生舍入/溢出不能统一的转换使用 `nativeCast` 明确标注；该纵向实现不代表本设计的完整类型目录已经完成。

## 1 五层归属

| 层次 | 放什么 | 主要位置 | 使用者看到什么 |
| --- | --- | --- | --- |
| L0 既有 EasyQuery | 比较、普通字符串/数值/日期函数、聚合、基本 JSON、查询链、事务等 | 上游原 API；HANA 在适配器补翻译 | 继续写 `column.toLower()`、`query.where(...)` 等原调用 |
| L1 通用 DSL 增强 | 精确类型/CAST、完整 ROWS、组合所需 JSON path、NULL-safe 比较、NULLIF、日期截断 | `easy-query-pro-dsl` 定义契约；三库增强模块提供翻译 | 同一种表达式在三个数据库表达相同业务含义 |
| L2 数据库类型 DSL | JSONB、SQL 数组、MySQL JSON/unsigned/TIME 时长、PG interval、HANA 受限长文本等 | 对应的 `easy-query-pro-dsl-*` | 传入或选择数据库类型后，获得准确的方法集合 |
| L3 数据库查询 DSL | 多列全文、RANGE 窗口、HANA 层级、计算视图和表函数 | 对应方言增强模块的 `query`/`window`/`search` | 有查询作用域、表来源或多列表达式的专属入口 |
| L4 方言适配 | SQL 语法、绑定、结果读取、版本能力、元数据和 DDL | HANA 新适配器；MySQL/PG 复用上游并补必要扩展 | 常规调用不出现数据库分支 |

**物理类型不同，不等于需要不同的操作 DSL。** MySQL DECIMAL、PG NUMERIC、HANA DECIMAL 都以通用数字表达式为主；JSONB 和 SQL 数组才需要不同的方法集合。全文检索、层级和视图涉及整个查询，不能仅因为它们使用某种列就塞进列类型。

共享的参数、类型描述、path 和窗口模型可以放在 L1，但这不意味着每一种原生能力都成为三库通用公开方法。代码复用与 API 的可移植性承诺分别判断。

## 2 L0 已有通用能力的处理

这些能力纳入完整验收，但不在增强包重新创建平行 API：

| 领域 | 现有能力 | 本项目要做的工作 |
| --- | --- | --- |
| 条件 | 比较、IN、范围、NULL、EXISTS、逻辑组合、LIKE | 复用；补 HANA 语义与组合测试 |
| 字符串 | concat、lower/upper、substring、trim、replace、pad、length、indexOf、joining | 复用；处理索引起点、Unicode、NULL、LIKE 转义和数据库长度语义 |
| 数值 | 四则运算、abs、round、数学函数、跨列最大/最小值 | 复用；处理精度、返回类型和溢出，不重做 ProMath |
| 日期 | now/utcNow、提取、格式化、加减、时间差、比较 | 复用；日期截断另有明确增量，见 L1 |
| 聚合 | count/sum/avg/min/max、distinct、条件聚合 | 复用；不再发明一套条件聚合方法 |
| 窗口 | row number、rank、dense rank、窗口聚合、LAG/LEAD、FIRST/LAST/NTH_VALUE | 复用已有函数；新增 frame 与必要的组合规格 |
| JSON 读取 | `asJSONObject/asJSONArray`、getString/getInteger/getBigDecimal、子对象/数组、key、数组长度/索引 | 复用现有 Java 接口与底层函数；仅补 path/结果组合缺口 |
| 查询与写入 | JOIN、子查询、UNION、CTE、分页、更新、删除、冲突写入、事务 | HANA 做后端适配，不新增一套 ProQueryable 或通用 ProUpsert |
| 映射与业务机制 | APT、DTO、导航、ValueConverter、逻辑删除、过滤器、拦截器 | 验证兼容，不复制实现 |

本地已核对 `SQLFunc`、`SQLJsonFunc`、`ColumnStringFunctionAvailable`、`ColumnJSONObjectFunctionAvailable`、`ColumnFunctionCastJSONAvailable`、窗口扩展等。不能只看 JDBC 类型注册就推断 APT 会自动生成对应的专属 DSL。

## 3 L1 通用核心明确新增什么

### 3.1 公开能力目录

| ID | 通用新增 | 具体契约 | 为什么需要新增 |
| --- | --- | --- | --- |
| U01 | 精确类型 CAST 与 typed null | 按长度、precision/scale 和目标类型生成真实 SQL 转换，返回准确表达式；typed null 复用原有常量/绑定机制 | 上游 Class-based CAST 不足以表达这些参数；不重复实现已有普通 cast |
| U02 | WindowSpec 与完整 ROWS | partition、order、合法起止边界、前后 N 行、current/unbounded；复用已有窗口函数 | 当前 `OverExpression` 没有 frame 入口 |
| U03 | 结构化 JsonPath 与可组合 JSON 谓词 | key/index 路径、参数/转义、必要的布尔结果表达式；标量读取仍委托上游 | 原字符串 key 和部分直接追加条件的方法不足以覆盖安全路径与 select/where 组合 |
| U04 | NULL-safe equality/distinct | 两端 NULL 视为相等；只一端 NULL 视为不等；结果为二值而不是 UNKNOWN | 当前 `equalsWith` 使用 simple CASE，不等于这个契约 |
| U05 | NULLIF | 相等时返回带正确类型的 SQL NULL，不等时返回左值；NULL 与数值类型规则明确 | 当前未找到公开 SQL DSL 方法，上游测试存在手写 `nullif(...)` |
| U06 | 数据库端日期截断 | LocalDate/LocalDateTime 的年、季度、月、ISO 周、日及适用的时/分/秒起点 | `EasyUtil.getDayStart/getMonthStart` 是 Java 计算，不是列上的数据库表达式 |
| U07 | 前缀 ROLLUP 与汇总行标识 | 多维汇总与真实 NULL 区分，复用 GroupingN 结果模型 | 上游缺少多级汇总入口，详细范围见 A02 |
| U08 | 总体/样本方差和标准差 | 四种统计语义与明确结果精度，未验证的分片合并拒绝 | 上游缺少对应统计入口，详细范围见 A03 |

U04–U06 对应蓝图新增的 C03。它们是这次细化后明确补入的通用增量，不把所有可以用原接口完成的操作重新包装成新方法。

U04 必须验证四种 NULL 组合和 NOT 后的结果。HANA 的等价实现不能仅写 `(a = b OR bothNull)`，因为一端为 NULL 时仍可能出现 UNKNOWN。对非确定性或无法保证求值语义的复杂输入，要求先投影为列引用，不通过重复执行表达式改变结果。

U06 对日期值只允许日期粒度，对日期时间允许完整粒度；ISO 周固定为星期一，不能跟随会话地域设置。带时区时间点必须先明确转换到哪个日历时区，不能用 JVM 默认时区猜测。它不是把日期格式化成字符串再把字符串当日期返回。

### 3.2 通用类型与原有表达式的关系

| 逻辑类型/描述 | Java 结果 | 操作归属 |
| --- | --- | --- |
| signed integer / exact integer | Short/Integer/Long 等明确类型 | 原有数字比较和计算 |
| decimal(p,s) | BigDecimal | 原有数字 DSL；新增精确 CAST 参数 |
| real/double | Float/Double | 原有数字 DSL；精度和特殊值能力按数据库检查 |
| boolean | Boolean | 原有布尔表达式和条件组合 |
| bounded Unicode text | String | 原有字符串 DSL；长度和字符集规则明确 |
| large text / character LOB | String 或明确的流读取模式 | 共享读写契约；不承诺在三库都能进行所有普通字符串、比较、排序操作 |
| date / time-of-day / local datetime | LocalDate/LocalTime/LocalDateTime | 原有日期 DSL与 U06 |
| instant | Instant | 明确 UTC 映射；不等于保存原始时区或 offset |
| binary / large binary | byte[] 或明确的流读取模式 | 绑定、读取、适用比较；不自动获得字符串操作 |
| UUID | UUID | 复用上游 UUID/转换机制；存储方案在方言层确定 |

`CommonTypes` 描述逻辑意图，不承诺每一类型之间都允许 CAST。存储可用、参数可绑定、可作为 CAST 目标和可进入某个 SQL 子句，是不同能力。UUID、LOB、二进制等尤其不能仅凭 Java Class 放开所有转换。

通用 decimal 参数取三库可共同实现的范围，并明确数值舍入与越界契约；更宽或特殊 scale 进入方言类型。字符串长度必须说明单位，不能混淆字节、Unicode 码点与 Java UTF-16 长度；字符截断、二进制填充以及时间精度也不能靠同一个类型名字假装一致。对无统一转换契约的组合使用明确的原生转换入口。

### 3.3 只共享基础设施，不新增业务 DSL

- 类型描述、结构参数校验、结果类型与可空性信息。
- 当前 runtime 的方言/版本/能力信息、缺失配置与错库诊断。
- 列、函数、参数、别名、聚合/窗口分类的上游桥接。
- 受控 JsonPath、类型名与标识符、窗口边界等结构值。
- 公共测试契约和错误信息规范。

复用上游 SQLFunction/segment/selector，不构建另一个通用 SQL AST。普通参数绑定仍由上游完成，新增层只携带其尚不能表达的语义。

## 4 类型描述怎样决定对应 DSL

需要保留两个维度：**Java 值类型**与**SQL 类型族/能力**。一个以 String 保存的 JSONB 不应自动拥有 upper/trim；普通 VARCHAR 也不应出现 JSONB 包含操作。

建议采用类型族重载，而不是一个返回任意泛型的万能工厂：

| 类型描述/选择入口 | 得到的表达式视图 | 可以继续做什么 |
| --- | --- | --- |
| 通用/方言数字目标 | 对应 Java 数字类型的 NumberTypeExpression | 比较、算术、聚合 |
| 普通字符目标 | StringTypeExpression | 普通字符串操作 |
| MySQL JSON | MySqlJsonExpression | 通用 JSON 读取 + MySQL JSON 原生操作 |
| PG JSON | PgJsonExpression | JSON 读取；需显式转换为 JSONB 才获得 JSONB 专属能力 |
| PG JSONB | PgJsonbExpression | 通用读取 + JSONB 包含、路径修改等 |
| PG ARRAY(elementType) | PgArrayExpression | 基于元素类型的数组操作 |
| PG tsvector / tsquery | PgTextVectorExpression / PgTextQueryExpression | 文本检索组合、匹配、rank |
| MySQL TIME 时长 | MySqlDurationExpression | 时长语义，不伪装成 LocalTime |
| PG interval | PgIntervalExpression | 日历间隔语义，不伪装成固定秒数 |
| HANA NCLOB/CLOB/BLOB/TEXT | 受限 LOB/文本视图 | 只开放数据库允许的操作，必要时显式转换到较小标量 |

没有独特操作集的物理类型共用同一表达式族，不为每个 VARCHAR、INT、DECIMAL 定义一套重复类。Java 结果类型保留实体或转换器的契约；不要求业务实体统一改成供应商包装类。

类型描述可以区分 `StorageType`、`CastTarget` 和类型视图所需信息。允许用不同工厂返回受约束的描述类型，避免把只能用于 DDL 的类型传给 cast。不要设计 `<E> E as(SqlType)` 这种靠调用方强转猜返回类型的接口。

结果必须随着操作正确改变：JSONB 提取文本后回到 StringTypeExpression；数组长度回到数字表达式；匹配返回布尔；全文 rank 返回明确数值类型。不能所有方法都返回原来的专属类型。

聚合结果也不能机械复制输入列类型：sum/avg 等须核实数据库返回类型并保持既有契约或明确的增强契约。Java 类型系统不会自动证明 SQL 可空性，因此还必须记录和测试 NULL 行为，不能用 primitive 默认值掩盖 SQL NULL。

`view` 对未知物理元数据是调用者的类型声明，不能被描述成已经验证数据库 schema。已知声明冲突时立即拒绝；未知时保持离线构建能力，并提供独立的 schema/连接核对。普通文本列需要转换到 JSONB 时应显式 cast，而不是通过 view 悄悄生成转换。

### 按列和表达式选择绑定

同为 String 的字段可以分别是 VARCHAR、JSONB 或 NCLOB，同为 List 的字段也可以是 JSON 数组或 SQL ARRAY。不能全局把 String.class 注册成 JSONB，或把 List.class 注册成 PG ARRAY。

| 参数/结果来源 | 转换规则 |
| --- | --- |
| 业务实体或普通值对象 | 按该列既有 ValueConverter 和 handler 处理，避免重复序列化 |
| 明确的 JSON 文档参数 | 按已经编码的 JSON 文本绑定，不再次当业务对象序列化成包装对象 |
| 普通 String 参数 | 保持普通字符串含义，不能因为邻近 JSON 表达式而自动变成 JSON 文档 |
| JSON/数组提取出的标量结果 | 使用标量结果类型，不把原整列对象转换器继续套在子值上 |
| 专属数组、interval、unsigned 等 | 使用当前列/表达式的明确类型和 handler，不能污染同 client 的其他字段 |

以上选择复用上游的列级类型与转换机制；新增 SQL 参数需要携带必要的目标类型信息，不能再建立一条绕开原事务和绑定流程的执行通道。

### CAST、视图和映射不能混用

- `cast`：改变数据库计算中的类型，必须生成相应 SQL。
- `view`：给已有 SQL 类型提供正确的操作接口，不隐式转换存储，也不修改原列的 property type。
- `ValueConverter/JdbcTypeHandler`：负责 Java 表示与 JDBC 读写，不自动生成新 SQL，也不改变 APT 的方法集合。

DDL 仍通过上游迁移模型接入。类型描述的 DDL 输出或注册结果交给既有解析器；不能要求旧 `@Column` 注解接受 Java 工厂调用，也不通过 DSL 调用偷偷修改实体 schema。[jOOQ 对 CAST 与类型视图的区分可作为参考](https://www.jooq.org/doc/3.21/manual/sql-building/column-expressions/cast-expressions/)。

## 5 MySQL 类型与专属操作

### 5.1 类型目录

| 类型族 | 纳入方式 | 对应 DSL / 重要边界 |
| --- | --- | --- |
| signed 整数、DECIMAL、FLOAT/DOUBLE | 类型声明、绑定、读取、适用 CAST | 通用数字表达式，不重复加减乘除 |
| unsigned 整数 | 完整类型描述与值域/读写 | 通用数字操作；UNSIGNED BIGINT 必须容纳 BigInteger 范围，不能塞入 Long |
| CHAR/VARCHAR/TEXT 各族 | 长度、字符集/排序规则元数据、读写 | 普通字符串 DSL；DDL 名称不直接当 CAST 目标 |
| BINARY/VARBINARY/BLOB | 读写和明确转换 | 二进制视图；不能默认把 byte[] 当 SQL 数组 |
| BOOLEAN、BIT | 根据声明区分布尔和位串 | 不把所有 TINYINT(1) 或 BIT(n) 全局强转 Boolean |
| DATE、DATETIME、TIMESTAMP | 区分日期、本地时间与时间点映射 | 通用日期操作；TIMESTAMP 与 DATETIME 的会话时区行为不可混淆 |
| TIME | time-of-day 与 duration 显式区分 | MySqlDurationExpression；负值和超过 24 小时的值不能截断成 LocalTime |
| JSON | 完整原生类型视图 | MySqlJsonExpression |
| ENUM、SET | 既有类型/允许值的元数据、显式转换器与绑定 | 复用普通值比较；不新建枚举框架，不承诺 CAST AS ENUM/SET |

MySQL TIME 可以表示时长，因此与通用 LocalTime 分开；具体值域、fractional precision 和读写按 [MySQL TIME 文档](https://dev.mysql.com/doc/refman/8.4/en/time.html) 验证。

### 5.2 MySqlJsonExpression 的确定操作

| 操作 | 语义 / 输出 |
| --- | --- |
| 普通 field、object、array、scalar、length、key | 复用既有 JSON API；保留静态结果类型 |
| containsJson(document, optionalPath) | JSON_CONTAINS 语义，布尔结果 |
| containsAnyPath / containsAllPaths | JSON_CONTAINS_PATH 的 one/all 模式 |
| setPath / setPaths | JSON_SET；返回 JSON 表达式，可传入原有 update setter |
| removePath / removePaths | JSON_REMOVE；返回 JSON 表达式 |

JSON 文档参数与普通 SQL String 参数必须可区分。路径和文档都走正确的绑定/受控渲染，不能拼接业务值。SQL NULL、JSON null、缺失路径分别测试。此目录不额外扩展到整个 JSON 函数全集。

MySqlDurationExpression 需要完整 Duration 读写、比较、固定时长加减及精确秒数投影。边界与异常行为按已声明的 SQL mode 验证，不修改用户会话模式来悄悄改变应用语义。

## 6 PostgreSQL 类型与专属操作

### 6.1 类型目录

| 类型族 | 纳入方式 | 对应 DSL / 重要边界 |
| --- | --- | --- |
| SMALLINT/INTEGER/BIGINT、NUMERIC、REAL/DOUBLE | 类型声明、读写、精度和适用 CAST | 通用数字 DSL；PG 特殊 scale 由 PgSqlTypes 明确表达 |
| CHAR/VARCHAR/TEXT、BYTEA、BOOLEAN | 完整基本映射 | 通用标量操作；BYTEA 不等于 SQL ARRAY |
| UUID | 复用已有 PG UUID handler | 通用 UUID 值操作，不重新实现一个 UUID 框架 |
| DATE/TIME/TIMESTAMP/TIMESTAMPTZ | 明确本地时间与时间点 | 普通日期 DSL + 必要的原生时区转换；不承诺保留输入的原始 offset |
| JSON | 原生 JSON 视图 | 不冒充 JSONB；缺少的原生操作不能直接套 JSONB 运算符 |
| JSONB | 完整 JSONB 视图 | PgJsonbExpression |
| 一维 SQL ARRAY | 元素类型、绑定/读取和运算 | PgArrayExpression；Java List/数组形态与元素类型保留或显式转换 |
| INTERVAL | 独立的日历间隔值与读写 | PgIntervalExpression；保留 months、days、time 部分 |
| TSVECTOR / TSQUERY | 检索表达式和必要的映射 | 两种专属视图，不暴露普通字符串方法 |
| 已有 enum/domain | 带 schema 的引用、元数据和显式转换器协作 | 继承合法基础操作；不自动创建或迁移用户定义类型 |

PG interval 的月份、天数和时间部分具有独立含义，不能无条件折算成 Duration；timestamptz 也不是“保存原始时区字符串”的类型。[PostgreSQL 日期时间类型](https://www.postgresql.org/docs/17/datatype-datetime.html)

### 6.2 PgJsonbExpression 的确定操作

| 操作 | 语义 / 输出 |
| --- | --- |
| scalar/object/array/key/path 读取 | 复用共同读取；返回标量时回到通用表达式 |
| containsJson / containedBy | `@>` / `<@` 原生包含语义 |
| hasKey / hasAnyKey / hasAllKeys | 原生 key 存在判断；布尔结果可用于投影和过滤 |
| pathExists | 明确的 PG jsonpath；不能把任意 PG path 当成通用 JsonPath |
| setPath | jsonb_set，并显式控制是否创建缺失项 |
| removeKey / removePath | 原生 key/路径删除，返回 JSONB 表达式 |

PG JSON 的额外方法只限可实现的读取与显式向 JSONB 转换；不让 JSON 类型自动获得 JSONB 的比较和修改方法。[PostgreSQL JSON 类型](https://www.postgresql.org/docs/17/datatype-json.html)

### 6.3 PgArrayExpression 的确定操作

- 基于 String、Integer、Long、BigDecimal、Boolean、UUID、LocalDate、LocalDateTime 的一维数组绑定与读取。
- `anyEq` 等明确选定的 ANY 比较、`containsAll`、`containedBy`、`overlaps`。
- 总元素数与维度长度分别命名，区分空数组结果；不能把所有 length 含义当成一样。
- 元素访问保留元素 Java 类型，并明确 PostgreSQL 下界；普通零基 List 使用与数据库原生下标不能混用。非默认下界不能在读取时静默丢失，需保留下界或明确拒绝该结果映射。
- NULL 数组、空数组、NULL 元素分开处理；关闭 JDBC Array 资源。

`UNNEST` 会改变行数，属于查询来源能力，不能当作普通数组标量方法顺手加入。本范围不因此自动加入任意 LATERAL/表参数体系。[PostgreSQL 数组操作](https://www.postgresql.org/docs/17/functions-array.html)

PgIntervalExpression 提供完整值读写、间隔加减/取反及与相应日期时间的原生加减。比较和日历运算保持 PG 语义；需要固定秒数时使用显式转换和适用条件，不把含月间隔当作固定时长。

## 7 HANA 类型与专属操作

### 7.1 类型目录

| 类型族 | 纳入方式 | 对应 DSL / 重要边界 |
| --- | --- | --- |
| SMALLINT/INTEGER/BIGINT、DECIMAL、REAL/DOUBLE | 完整普通映射 | 通用数字操作；Java signed 值域与数据库类型值域分别校验 |
| TINYINT、SMALLDECIMAL | 明确原生值域/精度的描述与映射 | 仍属数字表达式，但不能悄悄替代业务的 signed byte 或精确金额类型 |
| VARCHAR/NVARCHAR | 明确非 Unicode/Unicode 与长度 | 普通字符串 DSL；默认 Unicode 业务字符串采用对应 Unicode 映射 |
| ALPHANUM | 原生类型声明、读取/绑定与明确转换 | 保留数字样式字符串的原生比较特征，不自动 strip/pad 后当普通代码值 |
| SHORTTEXT | 存储/索引属性与读取 | 查询结果按实际字符类型处理；不是可任意拿去 CAST 的独立标量类型 |
| DATE/TIME/SECONDDATE/TIMESTAMP | 角色与精度清晰的映射 | 普通日期 DSL；SECONDDATE 的精度差异不由 Java 类型掩盖 |
| BOOLEAN | 完整绑定、投影与谓词适配 | 复用布尔 DSL，区分 SQL 条件和结果列 |
| BINARY/VARBINARY/BLOB | 二进制数据与 LOB 生命周期 | 普通绑定/读取和受支持操作 |
| CLOB/NCLOB/TEXT | 受限文本/LOB 视图 | 明确可用操作与子句，不能因为 Java 是 String 就放开全部字符串/排序操作 |
| JSON 文本 | 在已声明 NVARCHAR/NCLOB 等之上提供视图 | HanaJsonTextExpression；不发明一个 HANA JSONB 物理类型 |

HANA SHORTTEXT 和 LOB 的 SQL 使用限制会直接影响 DSL。类型视图要保留 SQL 类型信息；不能只保留 Java String 后忘记 GROUP BY、ORDER BY、DISTINCT 等限制。[SAP HANA SQL Reference](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)

### 7.2 HANA 类型 DSL 的确定行为

- 普通数字、日期、NVARCHAR 等复用通用操作，只保留精度、存储和转换差异。
- HanaJsonTextExpression 支持目标版本可实现的共同 JSON 读取/判断，不伪装成 MySQL JSON 或 PG JSONB 的完整原生修改能力。
- LOB 视图只开放经过验证的 NULL、读取、长度、切片或检索操作。需要普通小字符串操作时显式转换；转换后再回到通用字符串表达式。
- `CONTAINS/FUZZY/SCORE` 归入查询级搜索入口。可以引用相应文本类型，但不能让每个 String 方法自动变成模糊检索。
- hierarchy、计算视图的输入参数和结果列归查询来源/关系模型，不命名成某个虚构的 HanaColumn 类型。

HANA ARRAY 的存在不意味着其运算和 JDBC 协议等于 PG ARRAY；本次确定范围不新增 HANA SQLScript 数组体系。BINTEXT 的文档处理流水线同样不自动并入普通二进制/文本列 DSL。

## 8 L3 查询级方言具体做什么

| 查询能力 | MySQL | PostgreSQL | HANA | 为什么放查询级 |
| --- | --- | --- | --- | --- |
| 全文过滤/评分/排序 | MATCH/AGAINST，natural/boolean 模式 | tsvector/tsquery、匹配与 rank | CONTAINS、FUZZY、SCORE | 涉及多列、索引、语言/模式和当前查询的评分作用域 |
| 原生 RANGE frame | 做；检查排序键、数值/时间边界 | 做；检查排序键、数值/interval 边界 | 当前基线不承诺支持；明确拒绝 | 不能把范围语义偷偷变成 ROWS；共用规格模型不等于通用能力 |
| 普通 CTE/递归 CTE | 复用上游与现有适配 | 复用上游与现有适配 | 普通 CTE 适配；任意递归 CTE 明确不支持 | 关系查询结构，由适配器处理 |
| 邻接树/森林 | 复用既有树能力 | 复用既有树能力 | HIERARCHY 适配与专属祖先/后代等 | 返回关系/层级信息，不是标量列操作 |
| 参数化计算视图 | 不提供假等价 API | 不提供假等价 API | 做；标量/多值输入及结果映射 | 参数改变查询来源，不是普通字段值 |
| 选定表函数来源 | 不借此新建通用表函数框架 | 不借此扩展任意 UNNEST/LATERAL | 做；声明输入/结果列并继续组合查询 | 会改变结果列与行数 |

MySQL/PG 的 RANGE 可以共享底层构造逻辑，但公开为对应方言的窗口能力。通用窗口核心承诺完整 ROWS；不得由于两库支持就把 RANGE 宣称为三库通用。

全文查询必须创建或引用搜索上下文。SCORE/rank 表达式只允许组合到相应查询，不能从一个查询取出后套到另一个 query。三个数据库的分值不承诺可横向比较。

HANA 已采用 [原子搜索来源](hana-fulltext.md)：CONTAINS 与 SCORE 在同一物理表 SELECT 构造，随后以独立 DTO 输出。FUZZY 阈值和搜索词均绑定；SMART 展示过滤保留在外层，根级 OR 条件整体包组。HANA 88 的多个评分子查询 JOIN 限制、UNION ALL 支持，以及上游动态 schema JOIN 的投影边界分别记录，不把数据库原生限制伪装成通用功能。

MySQL 的 [MATCH 查询来源](mysql-fulltext.md) 使用 natural language/boolean 两模式，显式安装 query scope，匹配和 Double 分数同时构造。布尔负贡献仍按原生 MATCH 谓词处理，不硬编码分数大于零；InnoDB 的提交后索引可见性与 HANA 分别验证。PG 的 tsvector/tsquery 及 rank 继续作为专属类型/操作实现，不借用字符串 LIKE 或通用分数。

PG 的 [tsvector/tsquery 值类型](pgsql-text-search-types.md) 已接入原生 JDBC、DDL/CAST 与基础标量映射。编码对象与普通文本分开，数据库语法/规范化留给 PostgreSQL；缓存读取保留 PGobject 的实际类型，未知二进制表示不会当作 SQL NULL。[PG 全文查询](pgsql-fulltext.md) 复用此基础，提供 plain/web/encoded query、受控 rank/cover-density 及归一化；配置名和输入保持绑定，匹配使用原生 @@，不以大于零的分数代替。

查询来源的重复部分集中在内部 `CapturedQuerySourceBuilder`：构造时克隆原 ClientQueryable、提取 CTE/表依赖，渲染时沿同一 ToSQLContext 委托参数，保留 visitor 与 clone。空投影边界防止外层 SMART 展示筛选进入已经完成的匹配/评分层；各方言继续负责来源约束、原生评分和最终查询的分片检查。这是已有查询树的组合基础，不引入新的执行器或公开搜索类型体系。

## 9 L4 底层方言实现清单

| 翻译/执行领域 | MySQL | PostgreSQL | HANA |
| --- | --- | --- | --- |
| 标识符、schema、保留字 | 复用上游 | 复用上游 | 新适配并完整验证 |
| SELECT/JOIN/子查询/UNION/分页/count | 复用上游，增强组合回归 | 同左 | 新适配，能复用的标准部分经测试后复用 |
| 既有字符串/数字/日期/JSON/聚合函数 | 复用既有 SQLFunc | 复用既有 SQLFunc | 逐操作补齐，不继承未经核实的默认语法 |
| 新精确 CAST | 增强模块翻译为合法目标语法 | 增强模块翻译 | HANA 增强模块翻译 |
| 新 NULL-safe 比较 | `<=>` 语义 | `IS NOT DISTINCT FROM` 语义 | 等价二值表达式；不能直接照搬 PG 语法 |
| 新 NULLIF | 标准函数及类型处理 | 同左 | 同左 |
| 新日期截断 | 保持日期类型的原生表达式组合 | date_trunc 等原生能力 | 保持类型与日历语义的原生表达式组合 |
| ROWS 窗口 | 共同模型，数据库验证 | 同左 | 同左，检查必要排序与函数限制 |
| JSON/数组/检索原生操作 | MySQL 增强实现 | PG 增强实现 | 共同 JSON 翻译及选定 HANA 专项实现 |
| 单条/批量写入与生成键 | 复用并回归 | 复用并回归 | 新适配与真实 JDBC/并发验证 |
| 冲突写入、锁和事务 | 复用上游契约 | 复用上游契约 | 完成蓝图 H05/H06，不能创建另一执行协议 |
| 新类型 handler / result mapping | 只补确有缺口者 | ARRAY/interval/检索等按需补齐，UUID 复用 | 基本类型、LOB、精度和明确缺失项 |
| 元数据与迁移 | 保留上游；补增强类型声明协作 | 同左 | HANA provider/parser 的完整范围 |

这个清单中的“复用”仍要求完整回归，不等于不管兼容性；“新适配”也不要求把上游所有代码复制一遍。

## 10 模块依赖与安装方式

扩展点遵循 EasyQuery 的类型与工厂模式。DSL facade 开放受保护构造和上下文挂载，数据库 facade 复用共同能力；facade 没有返回自身的链式方法时不强加 SELF 泛型。JSON/interval/duration 等保持同族的链式操作按泛型自身类型和可覆写创建钩子演进，类型变化仍返回准确的标量族。工厂允许替换单个 selector/provider/表达式构造，不要求复制完整工厂；clone/recreate 也必须保留扩展实现。新增扩展点用实际消费者编译与运行验证，不通过未经约束的 `<E> E` 猜测结果类型。

必须避免一个隐蔽的循环：通用 DSL 不能依赖 HANA 模块，而只依赖 sql-core 的 HANA 适配器也不能反过来引用 Proxy DSL 类型。

| 代码 | 所在模块 |
| --- | --- |
| 上游已有函数的 HANA 翻译 | `easy-query-pro-hana` |
| U01–U08 的公共 API、规格、桥接 | `easy-query-pro-dsl` |
| U01–U08 的 MySQL 实现与 MySQL 专属 API | `easy-query-pro-dsl-mysql` |
| U01–U08 的 PG 实现与 PG 专属 API | `easy-query-pro-dsl-pgsql` |
| U01–U08 的 HANA 实现与 HANA 专属 API | `easy-query-pro-dsl-hana` |

允许增加一个小的公共函数工厂/翻译契约，例如 `ProSqlFunctions`，由三库实现并返回上游 SQLFunction。它只覆盖本项目新增操作，不继承并转发整套 SQLFunc，不创建新执行器。原有 SQLFunc 保持由当前数据库适配器管理。

只使用普通 HANA EasyQuery 功能时依赖 HANA 适配模块即可。使用增强 DSL 时选择相应 `dsl-*` 模块，它传递依赖通用核心及所需适配器，并通过显式安装入口注册当前 runtime 的增强服务。公共核心单独存在不代表已安装某个数据库的增强翻译器。

安装意图如下，具体公开名称在编译原型中定型：

```java
var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
        .setDefaultDataSource(dataSource)
        .useDatabaseConfigure(new HanaDatabaseConfiguration())
        .customConfigure(HanaExtensions::configure)
        .build();
```

MySQL/PG 使用上游配置和各自增强安装入口。注册要校验方言身份、重复/冲突安装和版本，保留调用者已有选项与服务；不把插件加载到全局静态状态中。

`MySqlExtensions.configureMappings` / `PgSqlExtensions.configureMappings` 仅补齐缺少的默认 converter，重复调用不会替换已有实例。应用提供同类 converter 时先注册应用实例，再调用该方法补缺；上游原生 `applyColumnValueSQLConverter` 仍按具体类拒绝重复注册，并不是覆盖容器。这些初始化应在首次实体 metadata 加载前完成。

MySQL/PG 依赖完整数据库来源的高级查询另有显式 `MySqlExtensions.configureQueryScope(services)` / `PgSqlExtensions.configureQueryScope(services)`。普通 `configure` 不替换现有 ExpressionFactory，包括在 Pro 之前注册的应用工厂。以 MySQL 为例，需要查询作用域传播时按以下顺序安装：

```java
services -> {
    MySqlExtensions.configure(services);
    MySqlExtensions.configureQueryScope(services);
    // Register an application factory here if needed.
}
```

该显式方法注册 `MySqlProExpressionFactory`，只在 SELECT 渲染时把最外层查询标记放入 `ProQuerySQLContext`。参数、别名、SQLRewriteUnit 和根调用计数全部委托原 ToSQLContext；内层 CTE/子查询不能覆盖外层后来增加的分片状态。DML、表、CTE 和 UNION 的构造继续继承上游 MySQL 工厂。HANA adapter 使用自己的 sql-core 上下文，不反向依赖通用 Proxy DSL。

应用若替换该工厂，应在 `configureQueryScope` 之后注册，并继承 `MySqlProExpressionFactory` / `PgSqlProExpressionFactory` 或在自定义 SELECT 表达式中保留 `ProQuerySQLContext.withQueryContext`。仅依赖普通逐行 DSL 的应用无需新增这项安装。新的数据库专属来源必须在渲染时检查标记存在，不能把缺失标记等同于非分片而绕过约束。

## 11 统一的使用方式

推荐新公共标量能力通过 `ProDsl.of(entity)` 取得上下文，专属能力使用 `MySqlDsl/PgSqlDsl/HanaDsl.of(entity)`。无列常量也因此有明确上下文，不需要线程全局当前查询。

```java
// Current scalar and JSON entry points; order represents an application's generated proxy.
var pro = ProDsl.of(order);
var amount = pro.nativeCast(order.amount(), CommonTypes.decimal(19, 4));
var denominator = pro.nullIfValue(order.quantity(), 0);
var month = pro.truncateDateTime(order.createdAt(), TruncationUnit.MONTH);
var sameOwner = pro.nullSafeEquals(order.ownerId(), order.reviewerId());

var pg = PgSqlDsl.of(order);
var attributes = pg.view(order.attributes(), PgSqlTypes.jsonb());
var matched = attributes.containsJson(JsonInput.encoded("{\"active\":true}"));
var changed = attributes.setPath(List.of("active"), JsonInput.encoded("true"), true);
```

所有新值方法先构造表达式。需要把布尔表达式加入 WHERE 时，通过上游明确的条件操作，例如 `matched.eq(true)`；用于 SELECT 时直接参与投影。不能让同一个 contains 方法一会儿修改条件、一会儿仅返回函数，造成漏条件或重复条件。

`view(column, type)` 采用类型族重载，使 JSONB、数组等获得正确接口。当前没有新增 `pg.jsonb(column)` 一类重复入口；普通数字和字符转换继续返回上游表达式族。

`JsonInput.encoded` 表达已编码 JSON，合法性由数据库解析；`sqlNull` 与 `jsonNull` 明确区分。实体序列化继续使用现有转换器，不全局改变 String 的含义。

APT 仍生成原有列类型。注册 JDBC handler 不会改变生成代码的方法集合。当前方案通过显式类型视图实现专属 DSL，不隐式要求修改每个实体；若以后要求 `entity.payload()` 直接返回 PG 专属列，必须单独设计真实的 APT SPI。

## 12 哪些不进入确定范围

统计与报表新增能力按 [专门蓝图](reporting-blueprint.md) 分到通用内部基础、共同分析规格、原生查询和组合验证；不增加 sumIf/countIf/percentage 等已有表达式的同义 API。

| 内容 | 决定 |
| --- | --- |
| PG range/multirange、inet/cidr、hstore、空间/复合类型全集 | 不自动扩展；这些需要独立值模型和查询契约，不因为属于 PG 就一并堆入 |
| HANA SQLScript 数组/过程语言、空间/图/向量全集、BINTEXT 文档处理 | 不并入当前标量/查询 DSL；不得假装与已选类型等价 |
| MySQL SET 专用查询语言、任意位运算/optimizer hint 全集 | 保留元数据/明确映射和上游扩展途径，不为物理类型数量制造方法数量 |
| 全库统一全文评分、统一 JSON 原生修改、统一 interval | 不提供虚假的统一语义；使用对应方言 API |
| 通用跨库 RETURNING、LATERAL/任意表参数、所有窗口语法全集 | 不利用本次分类偷偷扩大既定蓝图范围 |

这里排除的是未选入产品范围的独立能力，不能拿它替代已经列入的 JSON、数组、全文、HANA 层级/视图或 ROWS 实现。

## 13 本轮核对得到的具体证据

2026-10-19 在当前 HANA 2.00.088 基线上只读执行：

```sql
SELECT SUM(v) OVER (
  ORDER BY v ROWS BETWEEN 1 PRECEDING AND CURRENT ROW
)
FROM (SELECT 1 AS v FROM DUMMY UNION ALL SELECT 2 AS v FROM DUMMY) x;
```

返回 1、3。将 ROWS 换成 RANGE，返回错误 7：该窗口函数不允许 RANGE frame。因此本规划只承诺 HANA 的完整选定 ROWS，RANGE 归入 MySQL/PG 专属窗口支持；不能拿 ROWS 代替 RANGE。

在 HANA 用 `IS NOT DISTINCT FROM` 做 NULL 比较，返回错误 257；新增通用 NULL-safe 操作必须有正确的 HANA 翻译。上游 `EqualsWithSQLFunction` 当前返回 simple CASE，不能把它直接视为 NULL-safe equality。

本地搜索还发现 `EasyUtil` 的日期截断是 Java 工具方法，上游测试中的 NULLIF 通过 rawSQLStatement 使用。最终实施仍要做逐操作全量审计，确认已有能力时应复用，而不是为新增清单机械写重复实现。

这些是设计可行性证据，不是新增 DSL 已实现的验收结果。

## 14 每个 API 的交付记录

实施时每个公开操作记录以下字段，并与蓝图能力 ID 对齐：

| 字段 | 必填内容 |
| --- | --- |
| 归属 | L0/L1/L2/L3/L4，所在模块，是否复用上游 |
| 输入 | Java 类型、SQL 类型族、类型/版本限制、允许上下文 |
| 输出 | Java 类型、SQL 类型族、可空性、是否仍保留专属操作 |
| 参数 | 列/值/path/类型名/结构参数的绑定或受控渲染规则 |
| 三库状态 | 原生实现、经证明等价实现、复用、明确不适用；禁止留下无说明空项 |
| 错误 | 编译错误、构造校验、能力拒绝或数据库数据错误的边界 |
| 测试 | 编译正负例、SQL/参数、真实执行、组合、边界、资源释放 |

通用操作必须通过三库同契约测试。专属操作既要证明本库正确，也要证明错误数据库、错误类型和错误上下文不会被默默接受。裸 CAST 的数据错误与类型视图错用是不同问题，不能都延迟到一条模糊的 SQL 异常。

## 15 与完整蓝图及实施顺序的对应

- D01/D02：本文件的类型目录、角色划分、显式视图与安装机制。
- D03/D04：MySQL JSON、PG JSONB/ARRAY 和必要共同读取桥接。
- D05：三库查询级全文；PG tsvector/tsquery 是其类型基础。
- C01：完整共同 ROWS 与 MySQL/PG RANGE，HANA 限制必须显式验证。
- C02：跨表达式、上下文、结果类型和错误契约。
- C03：NULL-safe equality/distinct、NULLIF、数据库端日期截断。
- H01–H10：普通 HANA 适配器职责；H11/H12：HANA 专属查询入口与底层配合。

执行顺序为：既有能力审计 → 基础 HANA 适配 → 类型/安装/桥接原型 → 共同标量与三库原生类型 → 泛型扩展点和窗口/报表 → U07/U08 汇总统计与查询级高级能力 → 完整组合、消费者与成熟度验收。高风险边界提前验证，不以大量空接口代替实现。

参考资料：[EasyQuery JSON 文档](https://github.com/xuejmnet/easy-query-doc/blob/main/src/en/prop/json-prop.md)、[MySQL 类型](https://dev.mysql.com/doc/refman/8.4/en/data-types.html)、[PG 类型](https://www.postgresql.org/docs/17/datatype.html)、[PG NULL 比较](https://www.postgresql.org/docs/17/functions-comparison.html)、[MySQL 比较运算](https://dev.mysql.com/doc/refman/8.4/en/comparison-operators.html)、[PG 文本检索](https://www.postgresql.org/docs/17/functions-textsearch.html)。
