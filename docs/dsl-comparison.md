# NULL-safe 比较

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
