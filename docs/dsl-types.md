# 精确类型表达式

当前已落地 DECIMAL 与有界 Unicode 文本路径，使用 EasyQuery 3.2.17 与 JDK 17。完整类型目录、列视图和方言类型仍按 [DSL 设计](dsl-design.md) 继续实施；本文只描述已经有实现的接口。

更大的 decimal 容量、PG 特殊 scale、HANA SMALLDECIMAL/浮点 DECIMAL 与 TINYINT 见 [原生数值目录](native-numeric.md)，继续使用本文的共同数字表达式入口。

## 安装与使用

在构建 client 前安装与数据库匹配的增强服务：

```java
var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
        .setDefaultDataSource(dataSource)
        .useDatabaseConfigure(new HanaDatabaseConfiguration())
        .customConfigure(HanaExtensions::configure)
        .build();
var query = new DefaultEasyEntityQuery(client);
var money = CommonTypes.decimal(19, 2);

var amounts = query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).value(new BigDecimal("19.99"), money).multiply(100));
var converted = query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).nativeCast(p.amount(), money));
var absent = query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).nullValue(money));
```

MySQL 使用上游 `MySQLDatabaseConfiguration` 和 `MySqlExtensions::configure`；PostgreSQL 使用上游 `PgSQLDatabaseConfiguration` 和 `PgSqlExtensions::configure`。增强安装不替换整个 SQLFunc，不打开数据库连接。相同增强重复安装幂等；混装与错误后端有明确异常。方言身份保存在当前 client 的服务中，不存在可变全局数据库设置。

`ProDsl.of` 在已有查询/写入 lambda 内使用。结果是原有 `NumberTypeExpression<BigDecimal>`，继续使用既有比较、算术、排序、投影与 setter。参数都经过原有 JDBC 绑定，业务值不拼接进 SQL。

## 参数和转换的不同保证

`CommonTypes.decimal(p,s)` 取三库共同声明范围：`1 <= p <= 38`，`0 <= s <= min(p,30)`。数据库更宽的范围留给方言类型。`StorageType<T>` 只提供 DDL 描述，`CastTarget<T>` 提供转换描述；`NumericType<T>` 同时具备二者，返回数字表达式。只有存储描述的对象不能传给表达式入口，编译负例覆盖这项限制。

`value` 在生成 SQL 前检查值能否精确表示，拒绝超出整数位或有效小数位的输入；尾零不算精度损失。绑定前将 BigDecimal 规范化为目标 scale，避免驱动对极端 scale 做无界扩展。`nullValue` 绑定真实 NULL，读取保持 null。所有结果类型使用包装类，不把 NULL 替换成 primitive 默认值。

`nativeCast` 明确保留数据库的原生转换行为。它不是无损转换承诺，也不暗中修改会话 SQL mode。当前实际执行结果如下：

| 输入和目标 | MySQL 8.4.11 | PostgreSQL 17.11 | HANA 2.00.088 |
| --- | --- | --- | --- |
| `12.3456` → `DECIMAL(19,2)` | `12.35` | `12.35` | `12.34` |
| `12.3456` → `DECIMAL(2,1)`，SELECT 上下文 | `9.9`，原生裁剪 | 错误 | 错误 |
| 精确 typed value `19.99` × `100` | `1999` | `1999` | `1999` |
| typed NULL | NULL | NULL | NULL |

MySQL 的写入/转换行为还受上下文和 SQL mode 影响，表中的 SELECT 结果不能推广为所有 DML 的保证。业务需要统一舍入时应显式选择既有数据库舍入函数，或在 Java 侧确定值后使用 `value`；不能把原生 CAST 的差异隐藏在通用名字下。相关基础规则见 [MySQL 8.4 CAST](https://dev.mysql.com/doc/refman/8.4/en/cast-functions.html)、[PostgreSQL 17 NUMERIC](https://www.postgresql.org/docs/17/datatype-numeric.html) 和 [HANA Platform SQL Reference](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)。

## 表达式、转换器与作用域

### 有界 Unicode 文本

`CommonTypes.unicodeText(maxUtf16Length)` 返回 TextType，容量范围为 1–5000 个 **UTF-16 单元**，与 Java String.length() 的单位相同。它不是字节数、Unicode 码点数或用户看到的字形数；例如 `😀a` 占 3 个 UTF-16 单元。

```java
var label = CommonTypes.unicodeText(40);
query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).value("O'Reilly 中😀", label).toUpper());
query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).nativeCast(p.name(), label).concat("!"));
```

文本入口返回原有 StringTypeExpression<String>，继续调用字符串函数。typed value 保留空字符串与 NULL，预先拒绝超过容量、未配对代理项和 PostgreSQL 不支持的 NUL；不进行规范化、替换字符或静默截断。输入使用完整 Unicode 字符集的连接，MySQL 声明和 CAST 显式使用 utf8mb4。

| 方言 | DDL 描述 | 原生 CAST 目标 |
| --- | --- | --- |
| MySQL | VARCHAR(n) CHARACTER SET utf8mb4 | CHAR(n) CHARACTER SET utf8mb4 |
| PostgreSQL | VARCHAR(n) | VARCHAR(n) |
| HANA | NVARCHAR(n) | NVARCHAR(n) |

`value` 的 UTF-16 容量保证输入能完整放入上述三种类型。DDL 返回的是物理类型声明，不会给其他写入方式自动增加 Java 校验或数据库 CHECK 约束；MySQL/PG 的物理 VARCHAR(n) 按字符计数，可能接受更多 UTF-16 单元。原有实体写入仍使用既有列 handler/转换器，不能把显式 typed value 的保证误认为所有写入都已改变。

`nativeCast` 继续保留原生截断差异。真实查询中将 `😀a` 转为长度 2，HANA 返回 `😀`，MySQL/PG 返回 `😀a`；它不承诺跨库相同的截断结果。若值已经在 Java 中，优先使用经过精确容量校验的 `value`。SQL 字符串比较、排序和大小写转换也继续遵循数据库排序规则。

三库共享 JdbcContract 现在使用该描述创建真实文本列，TextDslContract 验证 Unicode/引号/花括号绑定、空/NULL、已有 length/indexOf、结果类型、5000 单元上界和原生 CAST 差异。JDK 编译负例拒绝把 TextType 结果赋给数字表达式，或用文本描述绑定 BigDecimal。相关原生规则见 [PostgreSQL 17 字符类型](https://www.postgresql.org/docs/17/datatype-character.html)、[MySQL CAST](https://dev.mysql.com/doc/refman/8.4/en/cast-functions.html) 与 HANA 类型契约。

### 映射与作用域

普通属性作为输入时使用上游 `EasyColumnSegmentUtil` 的 SQL 属性转换。例如存储为分、属性转换为元的列，先取得该属性的 SQL 值，再执行 CAST。已构造的函数表达式保留其原有 SQL 语义，不遍历并重写它的内部列。CAST 输出只按目标数字类型读取，不再把业务 RowId/整列 ValueConverter 套在结果上。

返回表达式参与重复渲染、克隆、JOIN、相关子查询和写入 setter。相关子查询会先构造再挂入外层，表归属检查在最终 SQL 渲染时执行，使用上游表上下文；跨 client 使用立即拒绝。类型描述可以共享，查询表达式不能缓存为全局变量后用于无关查询。

聚合分类随原有 SQLFunction 保留。已知逐行 CAST 与组合可以在分片内执行；聚合后 CAST 和无法确认语义的包装继续拒绝，避免用 SUM/AVG 合并掩盖精度错误。具体范围及 Draft 投影修正见 [分片说明](dsl-sharding.md)，完整分片组合仍是蓝图中未关闭的验收项。

## 验证入口

- `DecimalDslTest`：三库离线 SQL、参数顺序、克隆、JOIN/相关子查询、setter、安装和作用域负例。
- `DslCompilationTest`：真实 JDK 17 消费者编译；错误值类型、错误结果族、错误 Java 数字类型、只有 StorageType 的输入均失败。
- `DecimalDslContract`：通过三个真实数据库执行相同消费用例；由 `MySqlConnectionIT`、`PostgresConnectionIT`、`HanaDecimalDslIT` 调用。
- `HanaConversionIT`：业务主键转换后投影为数字，以及 SQL 属性转换与精确 CAST 的协作。
- `TextDslTest,TextDslContract`：UTF-16 容量与合法性、三库真实文本绑定和 CAST；JdbcContract 同时验证物理 DDL。

这组证据不会替代后续字符长度单位、时间精度、原生 JSON/数组/interval、独立发布产物消费者和完整 CI 的验收。
