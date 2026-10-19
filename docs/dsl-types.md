# 精确类型表达式

当前已落地 DECIMAL 纵向路径，使用 EasyQuery 3.2.17 与 JDK 17。完整类型目录、列视图和方言类型仍按 [DSL 设计](dsl-design.md) 继续实施；本文只描述已经有实现的接口。

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

普通属性作为输入时使用上游 `EasyColumnSegmentUtil` 的 SQL 属性转换。例如存储为分、属性转换为元的列，先取得该属性的 SQL 值，再执行 CAST。已构造的函数表达式保留其原有 SQL 语义，不遍历并重写它的内部列。CAST 输出只按目标数字类型读取，不再把业务 RowId/整列 ValueConverter 套在结果上。

返回表达式参与重复渲染、克隆、JOIN、相关子查询和写入 setter。相关子查询会先构造再挂入外层，表归属检查在最终 SQL 渲染时执行，使用上游表上下文；跨 client 使用立即拒绝。类型描述可以共享，查询表达式不能缓存为全局变量后用于无关查询。

聚合分类随原有 SQLFunction 保留。跨分片的派生表达式 CAST 当前明确拒绝，因为“各分片转换后再聚合”不等价于“聚合后转换”；不会用看似成功的 SUM/AVG 合并掩盖精度错误。完整分片组合仍是蓝图中未关闭的验收项。

## 验证入口

- `DecimalDslTest`：三库离线 SQL、参数顺序、克隆、JOIN/相关子查询、setter、安装和作用域负例。
- `DslCompilationTest`：真实 JDK 17 消费者编译；错误值类型、错误结果族、错误 Java 数字类型、只有 StorageType 的输入均失败。
- `DecimalDslContract`：通过三个真实数据库执行相同消费用例；由 `MySqlConnectionIT`、`PostgresConnectionIT`、`HanaDecimalDslIT` 调用。
- `HanaConversionIT`：业务主键转换后投影为数字，以及 SQL 属性转换与精确 CAST 的协作。

这组证据不会替代后续字符长度单位、时间精度、原生 JSON/数组/interval、独立发布产物消费者和完整 CI 的验收。
