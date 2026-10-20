# 数据库专属数值目录

这些类型使用 `ProDsl.value`、`nullValue`、`nativeCast`，返回 EasyQuery 原有数字表达式。容量差异属于类型描述，比较、算术、聚合、排序继续复用上游 API。

| 入口 | Java 类型 | 声明范围 / 值域 |
| --- | --- | --- |
| `CommonTypes.decimal(p,s)` | BigDecimal | 三库共同范围，p 1–38，s 0–min(p,30) |
| `MySqlTypes.decimal(p,s)` | BigDecimal | p 1–65，s 0–min(p,30) |
| `PgSqlTypes.numeric(p,s)` | BigDecimal | p 1–1000，s -1000–1000；负 scale 需要 PostgreSQL 15+ |
| `PgSqlTypes.numeric()` | BigDecimal | 有限值，最多 131072 个整数位、16383 个小数位 |
| `HanaTypes.decimal(p,s)` | BigDecimal | p 1–38，s 0–p |
| `HanaTypes.smallDecimal()` | BigDecimal | SMALLDECIMAL，最多 16 位有效数字；下述实测指数边界 |
| `HanaTypes.floatingDecimal()` | BigDecimal | 无 p/s 的 DECIMAL，最多 34 位有效数字；下述实测指数边界 |
| `HanaTypes.tinyint()` | Short | 0–255；不改变 Java Byte 的 signed 映射 |

类型只接受对应方言。`value` 拒绝需要舍入、下溢或越界的值，尾零可精确移除；`nativeCast` 保留数据库转换行为。类型的 DDL 描述不会给普通实体 setter 自动加上同样的 Java 输入校验。

```java
var thousands = PgSqlTypes.numeric(2, -3);
query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).value(new BigDecimal("99000"), thousands));
// 99001 cannot be represented exactly; nativeCast is the explicit rounding path.
query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).nativeCast(p.amount(), thousands));
```

PostgreSQL 的负 scale 向小数点左侧取整，scale 大于 precision 则限制为较小的小数。例如 NUMERIC(2,-3) 的最大精确值是 99000，NUMERIC(3,5) 是 0.00999。没有 p/s 的 NUMERIC 不附加固定 scale，但仍有数据库实现上限。NaN/Infinity 无法用 BigDecimal 表示，显式原生转换得到这些值时，读取失败，不转换成零或 NULL。规则见 [PostgreSQL 17 NUMERIC](https://www.postgresql.org/docs/17/datatype-numeric.html)；MySQL 容量见 [MySQL 8.4 DECIMAL](https://dev.mysql.com/doc/refman/8.4/en/fixed-point-types.html)。

## HANA 浮点十进制与驱动边界

在 HANA 2.00.088、ngdbc 2.30.11 的实际 COLUMN TABLE 中验证：SMALLDECIMAL 最小非零正值为 `1E-398`，最大值为 `9.999999999999999E384`；浮点 DECIMAL 对应为 `1E-6176` 和 `9.999999999999999999999999999999999E6144`。负数对称。SMALLDECIMAL 的 16 位精度与最小量子要同时满足，例如 `9.999999999999999E-384` 仍会舍入，typed value 会拒绝。这里记录的是目标 revision 的存储实测范围，不把 [SAP SQL Reference](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf) 对 scale 的简短描述直接当作 Java BigDecimal.scale 的合法区间。

ngdbc 的 `setBigDecimal` 对负 scale 会展开整数，并可能拒绝数据库可存储的大指数；超出 decimal128 下界的输入还会出现错误指数。HANA 适配器的 BigDecimal handler 对负 scale、超过固定 decimal 整数容量和超小指数使用绑定的科学计数文本，让服务端执行目标类型转换。普通值继续使用 `setBigDecimal`，NULL 和读取沿用原有处理；不拼接数值 SQL、不引入额外查询。零先规范化，避免无意义的极端 scale。显式引用上游 BigDecimalTypeHandler 的列也经现有 handler alias 机制应用此修复，自定义 handler 仍可覆盖默认映射。

原生 CAST 与 raw entity 写入仍有舍入/下溢：SMALLDECIMAL 的 12345678901234567 转成 12345678901234570，浮点 DECIMAL 的 1E-6177 变成 0。使用 typed value 时，两种输入都提前拒绝；需要保留更多精度时选择固定 DECIMAL(p,s) 或其他合适模型。

## 验证

`NativeNumericDslTest` 验证范围、错库和已有表达式组合，`DslCompilationTest` 验证数字 Java 类型和结果族。`NativeDecimalContract` 由三库真实测试复用，包含真实 DDL、ORM 批量写入、完整实体、Draft、标量、typed NULL 和克隆。MySQL 覆盖客户端/服务端预处理及批量重写；PostgreSQL 覆盖文本/强制二进制 numeric；HANA 覆盖指数上下界、38 位固定小数、unsigned TINYINT、失败写入和事务回滚。

所有测试创建独立表/schema 并在作用域结束时清理。完整版本矩阵与组合验收继续纳入 CI；这组数值目录不会代替其他类型或整体成熟度验收。
