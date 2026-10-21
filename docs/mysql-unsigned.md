# MySQL unsigned 整数

存储类型的取值范围和 CAST 的结果类型分别建模。MySQL 的 `CAST AS UNSIGNED` 固定生成 unsigned BIGINT；较小宽度只提供存储描述、参数范围校验和数字参数表达式。这样不能把 unsigned INT 描述传给 nativeCast 后误以为数据库进行了 32 位范围检查。

| 工厂 | Java 类型 | 最大值 | 原生 CAST 目标 |
| --- | --- | --- | --- |
| unsignedTinyint | Short | 255 | 否 |
| unsignedSmallint | Integer | 65535 | 否 |
| unsignedMediumint | Integer | 16777215 | 否 |
| unsignedInt | Long | 4294967295 | 否 |
| unsignedBigint | BigInteger | 18446744073709551615 | 是 |

五种类型的下界都是 0。prepareValue 和参数入口保留 NULL，在执行前拒绝超范围；不能用 signed Byte/Short/Integer/Long 强行容纳对应宽度的完整 unsigned 范围。

```java
var mysql = MySqlDsl.of(row);
NumberTypeExpression<Long> limit = mysql.value(4294967295L, MySqlTypes.unsignedInt());
NumberTypeExpression<BigInteger> big = mysql.value(
        new BigInteger("18446744073709551615"), MySqlTypes.unsignedBigint());
```

较小类型的参数已校验可表示，SQL 用 64 位 UNSIGNED 表达该精确值。返回的是上游 NumberTypeExpression，继续使用已有数字运算、条件和聚合。unsignedBigint 实现公共 NumericType，可直接进入 ProDsl 的参数、NULL 和 nativeCast 入口。

MySqlDsl 对 unsignedBigint 提供精确的参数重载，消除它同时属于 Unsigned 与 NumericType 时的 Java 重载歧义；直接传 BigInteger 或 NULL 均可。该重载继续调用公共数字入口，应用继承 MySqlDsl 并覆盖数字参数方法时仍会收到调用。较窄 unsigned 类型没有因此获得原生 CAST 能力。

## 读写与原生运算

在首次实体使用前安装 BigInteger 精确 handler：

```java
MySqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
```

只为 BigInteger 增加缺失的结果映射，并保留应用先前注册的同类型 handler。写入继承上游 ObjectTypeHandler，由 Connector/J 原有 setObject(BigInteger) 保留 unsigned 参数标志。不能改成 setBigDecimal：服务端预处理下，直接 CAST 为 UNSIGNED 可能先按 signed 64 位截断到 Long.MAX_VALUE。读取以 getBigDecimal/toBigIntegerExact 处理，兼容 unsigned BIGINT、SUM 返回 DECIMAL 及 SQL 模式产生的 signed 整数结果。含小数的 SQL 结果会明确报错，不会向零截断。普通 String、Long 等映射不变。

实体仍使用既有声明，例如 `@Column(dbType = "BIGINT UNSIGNED") private BigInteger counter;`。其余宽度采用表中的 Java 类型。类型描述的 ddlType 输出可用于既有建表/迁移入口；实体任意写入仍受实际列约束与 MySQL SQL mode 控制，描述对象不会偷偷修改会话或给所有实体插入全局校验器。

nativeCast 保留 MySQL 行为，例如 -1 转 UNSIGNED 得到 18446744073709551615。普通 unsigned 减法可能报越界，而启用 NO_UNSIGNED_SUBTRACTION 会产生 signed 结果；增强层不改变该模式。类型描述约束的是存储/参数，计算结果继续遵循数据库及上游数字表达式契约。

验证入口：MySqlUnsignedTypeTest、MySqlUnsignedIT、DslCompilationTest。覆盖五种 DDL 与上下界、NULL、精确 BigInteger、两种预处理和两种减法模式、数据库拒绝越界后原值保持、错误 CAST 目标编译失败，以及 SUM 和小数拒绝。

依据：[MySQL 8.4 整数范围](https://dev.mysql.com/doc/refman/8.4/en/integer-types.html)、[CAST 类型和 unsigned 运算](https://dev.mysql.com/doc/refman/8.4/en/cast-functions.html)。
