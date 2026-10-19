# NULL-safe 比较与 NULLIF

`ProDsl` 新增 U04 的四个入口，返回上游 `BooleanTypeExpression<Boolean>`，可以继续投影、过滤、取反、排序和组合。增强安装方式见 [类型表达式说明](dsl-types.md)。

```java
query.queryable(Product.class).where(p ->
        ProDsl.of(p).nullSafeEqualsValue(p.name(), requestedName).eq(true));

query.queryable(Product.class).leftJoin(Product.class, (left, right) ->
        ProDsl.of(left).nullSafeEquals(left.name(), right.name()).eq(true));

query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).isDistinctFromValue(p.name(), null));
```

`nullSafeEquals` / `isDistinctFrom` 比较两个已有表达式；`nullSafeEqualsValue` / `isDistinctFromValue` 将业务值与左侧列比较。值方法刻意带 `Value` 后缀，避免 Java 在传入 null 时无法区分“表达式参数”和“业务值参数”的重载歧义；消费者编译测试覆盖该用法。

| 左值 | 右值 | nullSafeEquals | isDistinctFrom |
| --- | --- | --- | --- |
| NULL | NULL | true | false |
| NULL | 非 NULL | false | true |
| 非 NULL | NULL | false | true |
| 相等的非 NULL 值 | 相等的非 NULL 值 | true | false |
| 不相等的非 NULL 值 | 不相等的非 NULL 值 | false | true |

这项能力只改变 NULL 比较规则。非 NULL 的相等性继续使用数据库 SQL 类型与排序规则，不把 SQL 字符串比较改成 Java 字符串比较，也不为本来不能比较的 LOB 类型创造相等运算。

MySQL 使用 `<=>`，PostgreSQL 使用 `IS [NOT] DISTINCT FROM`，HANA 使用能匹配 NULL 的 `MAP` 再转为布尔结果。两项输入在模板中各出现一次，不用复制输入表达式的多个 CASE 分支。共享核心保存安装后的模板，数据库模块提供其翻译；没有按引号样式猜数据库或替换完整 SQLFunc。

普通列与业务值比较时，保留上游列元数据，由既有比较参数链选择 ValueConverter、加密和 JdbcTypeHandler；SQL 属性/值转换同样沿用上游。已构造的函数表达式继续保留其自己的转换规则。不会将业务对象先序列化一次，再交给列 handler 序列化第二次。

直接构造布尔表达式不追加 WHERE，使用 `.eq(true)` / `.eq(false)` 才添加谓词。查询/相关子查询复用与跨 client 拒绝共用已验证的作用域实现。跨分片派生表达式的当前限制仍适用，不能据此宣称整个 G4.6 已完成。

验证入口为 `NullSafeDslTest`、`NullSafeDslContract`、`DslCompilationTest` 与 `HanaConversionIT`。共享真实数据库契约覆盖全部 NULL 组合、投影、过滤、取反、业务值参数、Unicode/引号绑定、参数只出现一次、克隆和相关子查询。HANA 另外验证业务 Label 与 SQL 属性转换器，不要求实体改变 Java 类型。

原生语义参考：[MySQL 8.4 比较运算符](https://dev.mysql.com/doc/refman/8.4/en/comparison-operators.html)、[PostgreSQL 17 比较](https://www.postgresql.org/docs/17/functions-comparison.html)、[HANA Platform SQL Reference，MAP](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)。MAP 的 NULL 配对还通过本地 HANA 2.00.088 的独立 MCP 查询与适配器契约执行核实。

## NULLIF

U05 提供 `nullIf(left, rightExpression)` 与 `nullIfValue(left, value)`。两个值按数据库规则相等时返回 SQL NULL，否则保留左值；右值为 NULL 时仍保留左值。数字、文本、日期时间和布尔使用上游已有的类型族重载，因此不会把数字结果退化为 Object，或给普通字符串自动增加 JSON/数组操作。

```java
query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).nullIfValue(p.amount(), BigDecimal.ZERO).multiply(100));
query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).nullIfValue(p.name(), "hidden").toUpper());
query.queryable(Event.class).selectColumn(e ->
        ProDsl.of(e).nullIfValue(e.occurred(), null).year());
```

结果保留左侧的 Java 标量类型，日期精度、数值精度和字符比较仍受左侧 SQL 表达式及数据库规则约束。原来声明为 primitive 的实体属性在 NULLIF 投影中使用包装结果，不把 SQL NULL 填成默认 0/false。两个表达式的 Java 值类型必须兼容；混用 Integer/BigDecimal 应先显式选择转换目标，不能依赖一个万能泛型返回类型掩盖窄化。

同类型普通属性保留其既有结果映射，SQL 属性转换与比较参数转换复用上游。显式 `asStr()` 等视图忽略原整列转换器，这一规则继续传给 NULLIF 输出。业务值对象应先按实际 SQL 类型选择已有视图；本接口不建立任意对象的序列化体系。

实现使用三个数据库的原生 NULLIF，不手写重复输入的 CASE，也不改变事务或执行通道。原生函数的求值次数并非三库统一契约：MySQL 在两个参数不相等时可能两次求值左侧。对于随机值、序列等有副作用或非确定性输入，应先取得稳定值再比较；普通子查询也可能被优化器合并，不能仅凭加一层 SELECT 就承诺单次执行。相关规则见 [MySQL 8.4 NULLIF](https://dev.mysql.com/doc/refman/8.4/en/flow-control-functions.html) 与 [PostgreSQL 17 条件表达式](https://www.postgresql.org/docs/17/functions-conditional.html)。

`NullIfDslTest`、`NullIfDslContract` 和 `DslCompilationTest` 覆盖表达式族传播、绑定顺序、克隆、跨查询/跨 client 拒绝、相等/不等/NULL、字符与数字后续操作、LocalDate/LocalDateTime、布尔和 primitive 投影；`HanaConversionIT` 覆盖 SQL 属性转换器与显式字符串视图。完整方言类型目录及跨分片非线性结果合并仍按计划继续验收。
