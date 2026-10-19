# HANA 函数与结果语义

本说明对应 EasyQuery 3.2.17、HANA Platform 2.0 revision 88 和 ngdbc 2.30.11。它记录已实现路径的使用契约，不代替 [逐项审计](hana-function-audit.md) 与完整验收。普通表达式继续使用 EasyQuery 已有函数，调用者无需为 HANA 更换查询 DSL。

## 代码边界

`HanaSQLFunc` 只连接上游函数工厂；`HanaStringFunctions`、`HanaDateTimeFunctions`、`HanaDateTimeFormat`、`HanaCastFunctions` 与 `HanaJsonFunction` 分别处理各类翻译。它们复用上游 `SQLFunction`、参数绑定和表达式协议，不解析生成后的 SQL，也不建立第二套查询树。

## 字符串与 CAST

- `length`、`indexOf`、`subString` 按 Unicode 码点处理，与 MySQL/PostgreSQL 的数据库字符串语义对齐。HANA 原生 `LENGTH`/`LOCATE` 按 UTF-16 单元处理补充字符，因此内部用原生正则确定码点边界。它们不是 Java `String` 的 UTF-16 下标契约，也不按可视字素簇计数。
- `indexOf` 从 0 开始，找不到为 -1；`subString` 的公开起始位置继续从 0 开始。补充字符、换行、空结果和 SQL NULL 已通过真实执行验证。
- `leftPad`/`rightPad` 也按码点处理输入与补齐模式，先重复完整模式再截取；不能让原生补齐函数切开代理对。`empty`/`notEmpty` 使用长度判断，避免 HANA 忽略尾随空格的比较规则把纯空格归为空字符串。`blank` 仍沿用上游去除普通空格的含义，不扩展成 Java Unicode whitespace 判断。
- 普通字符串转换使用 `TO_NVARCHAR`。不能为方便直接加 `NVARCHAR(5000)`，因为 HANA 会静默截断较长的 NCLOB。
- Java `Byte` 的 SQL 转换使用有符号 `SMALLINT`，读取通过专用 handler 检查 -128..127。ngdbc 的 `getByte` 会回绕超范围值；适配器改为 SQLState `22003` 的明确异常。其他 handler 继续复用上游 registry，应用的显式注册仍有效。
- 上游按 Java class 的 `BigDecimal` 转换沿用 `DECIMAL(36,18)`。精确的可配置 precision/scale 属于通用类型 DSL 的后续工作，不能混为同一功能。
- LOB 到日期或布尔的转换需要先显式得到字符表达式，再做相应 CAST。不能把 NCLOB 与普通字符类型的转换能力视为相同。

## 聚合、窗口与布尔投影

COUNT/SUM/AVG/MIN/MAX、条件聚合、三种排名及五种聚合窗口继续使用上游实现。已有窗口的默认 peer frame、排序和分区已在 HANA 执行验证；这不等于后续新增的显式 ROWS/RANGE 已实现。

多列最大/最小值忽略 NULL，全 NULL 返回 NULL。HANA 原生 `GREATEST`/`LEAST` 传播 NULL，因此翻译时先以首个非空值填补空项。关联 UNION 子查询虽然单独可运行，revision 88 在当前组合 fixture 中触发 `ColDicVal ... not found` 优化器错误，故不采用该路径。当前标量翻译可能重复表达式，多列场景仍需纳入性能验收。

普通字符串聚合使用 `STRING_AGG`，分隔符绑定、可选排序、NULL 和空集已验证。`joining(..., true)` 的查询级去重尚未完成，当前在翻译阶段明确拒绝；这是未关闭的实现项，不能当作完整交付后的永久删减。

HANA 不能直接投影谓词。`booleanSQLFunction` 将谓词转换为保留 TRUE/FALSE/UNKNOWN 的 CASE 值，UNKNOWN 返回 SQL NULL，不悄悄转换为 false。原有的 `equalsWith` 则继续保持自身 CASE 规则，两者不混同。

## 查询组合的上游边界

LEFT/RIGHT/INNER JOIN、相关标量子查询、EXISTS、UNION/UNION ALL、普通 CTE、分页/count、NULL 排序与 clone 已有真实组合测试。EasyQuery 3.2.17 的 Queryable clone 共享可变 Proxy，不能把同一 Proxy 的两份 clone 直接当成自连接的两个来源。CTE 的第二个引用需有独立 Proxy 和表身份，例如：

```java
var second = new EasyEntityQueryable<>(TestProductProxy.createTable(),
        cte.getClientQueryable().toCteAs("eqp_selected"));
var joined = cte.cloneQueryable().leftJoin(second, (left, right) -> left.id().eq(right.id()));
```

`TestProductProxy` 是测试实体的生成类型，应用替换为自身 Proxy。统计分组结果数量，应先完成分组投影再 `count()`；直接对未投影的 group builder 调用 count 会沿用上游按组计数后求和的行为。测试和示例遵循这些已有契约，HANA 底层不复制 Proxy/Queryable 来修改通用行为。

## 日期与时间

日期加减使用原生 `ADD_DAYS`、`ADD_MONTHS`、`ADD_YEARS`、`ADD_SECONDS` 和 `ADD_NANO100`。HANA TIMESTAMP 的精度是 100ns：无法表示的纳秒增量在生成 SQL 前拒绝，常量换算检查溢出。

日期属性明确区分 Sunday=0 和 Monday=1 两套星期编号。`duration`/`duration2` 保持上游参数方向，完整单位向零截断；例如负半秒的完整秒数为 0，而非 Java `Duration.getSeconds()` 的 -1。

Java 格式支持 `y`/`yy`/`yyyy`、`M`/`MM`、`d`/`dd`、`H`/`HH`、`h`/`hh`、`m`/`mm`、`s`/`ss`、`S` 到九个 `S`、`a`，以及上游兼容的 `t`/`tt`。支持单引号包围的文本和两个单引号；普通文本通过参数绑定。小数第八、九位补零；未提供模式时采用 `yyyy-MM-dd HH:mm:ss.SSSSSS`。本地化月份、星期、周数、时区等未定义 token 明确拒绝。原生 HANA 格式通过 `dateTimeSQLFormat` 单独提供，格式作为绑定值。

## JSON 提取与容量

JSON 的标量与序列化片段分别使用 `JSON_VALUE`、带数组包装的 `JSON_QUERY`。保留 JSON null、缺失键和 SQL NULL 的区别；数字/布尔 getter 在 CAST 前取标量。`containsKey` 对存在但为 JSON null 的键返回 true。

上游成员和数组索引接口只能传常量路径。成员名使用括号路径并处理引号、反斜线、控制字符与 SQL 单引号；特殊键中的 `{0}` 不能成为模板占位符。无效 JSON 或输出容量不足使用 `ERROR ON ERROR`，避免静默变成 NULL。

配置按 client 隔离：

```java
var options = new HanaOptions(10_000);
builder.useDatabaseConfigure(new HanaDatabaseConfiguration(options));
```

`maxJsonValueUtf16Units` 限制标量输出的 UTF-16 单元数量；`maxJsonFragmentBytes` 限制 HANA 原生编码后的片段字节容量，两者不能用同一个“字符数”代替。单参数构造默认按 `6 * units + 2` 为 JSON 转义留出空间；数组包装另在内部增加两字节。需要独立设置时使用三参数 record 构造器。容量并非数据库全局配置，不同 client 可以安全使用不同值。

## JSON 数组长度的显式部署

HANA 2.0 的 `JSON_TABLE` 无法关联外层表别名，不能直接作为逐行数组长度的子查询。适配器提供一个小型 SQLScript 标量函数，用函数参数传入文档，在数据库内调用原生 JSON parser 并计数；不会把每行拉到 JVM 解析或增加网络往返。

部署到应用拥有的 schema：

```java
String ddl = HanaJsonSupport.createArrayLengthFunctionSql("APP_SUPPORT");
// Execute ddl once through the application's controlled migration process.
var options = new HanaOptions(5_000, "APP_SUPPORT");
builder.useDatabaseConfigure(new HanaDatabaseConfiguration(options));
```

`HanaJsonSupport` 只生成 `CREATE FUNCTION`，不会创建 schema、覆盖已有函数或自动连接。函数固定名为 `EQP_JSON_ARRAY_LENGTH`，使用 `SQL SECURITY INVOKER`，调用账号需要对应 EXECUTE 权限；部署账号需要目标 schema 的创建权限。未配置 schema 时，数组长度会在翻译阶段给出明确错误，其他 JSON 函数不受影响。

函数对 SQL NULL 返回 NULL、空数组返回 0；拒绝非数组和无效 JSON。原生 `JSON_TABLE` 的 lax 模式用于空数组，另检查顶层数组形状；没有用逗号计数等不可靠方式代替 JSON 解析。已通过临时 schema 的创建/删除、多行筛选/排序/投影、嵌套值、NULL 和错误路径验证。非管理员部署与迁移幂等仍属于后续完整验收范围。

原生语法依据：[HANA SQL Reference](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)、[ADD_NANO100](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/3962389a62b149b69d497bb25f4ec01a.html)、[JSON_QUERY](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/3126ea33d50d42d19517a08fe22ec5a1.html)。容量、Unicode 和 JDBC 细节以当前版本的集成测试作为可复现证据。
