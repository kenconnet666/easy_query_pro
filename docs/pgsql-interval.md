# PostgreSQL 日历间隔

当前基线为 PostgreSQL 17。`PgSqlTypes.interval()` 选择 PgIntervalExpression。值由 PgInterval 表示：有限值保留 int 月份、int 天数、long 微秒时间三个独立分量，另有正/负 infinity。不会把月转为 30 天或把日历日当作固定 24 小时。

```java
var month = PgInterval.of(1, 0, 0);
var type = PgSqlTypes.interval();
query.queryable(Event.class).selectColumn(e ->
        PgSqlDsl.of(e).value(month, type).addToDate(e.eventDate()));
```

日期加减 interval 返回 LocalDateTime；时间部分不会因输入是 DATE 而被截掉。LocalDateTime 入口保留本地日历语义，不隐式接受 Instant。月份运算、月底裁剪、比较、溢出与非有限算术均由 PostgreSQL 执行。

## 接入与映射

使用 PgSqlExtensions.configure 安装增强，并在首次实体元数据使用前执行：

```java
PgSqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
```

列可保持明确的值类型：

```java
@Column(dbType = "INTERVAL")
private PgInterval span;
```

普通 Duration/Period、String 及其他 handler 不会被替换。已有业务对象可通过列 ValueConverter 转成 PgInterval，并使用 PgIntervalTypeHandler 读取。view 保留原 Java 映射；nativeCast 明确生成 INTERVAL CAST 并以 PgInterval 读取。已知列声明冲突会拒绝，计算表达式或未声明物理类型的视图仍由调用者保证其含义。

PgSqlExtensions 通过公开 EntityMetadataManager SPI，为 PgArrayValue/PgInterval 和 [全文值类型](pgsql-text-search-types.md) 补齐基础标量元数据。自定义 metadata manager 的应用需要保留这些包装类型的标量处理；不会通过反射修改上游内部字段。JDBC 驱动仍是调用应用的依赖，当前验证版本为 pgJDBC 42.7.14。

## 运算和比较

| 操作 | 输出 |
| --- | --- |
| plus / minus / negated | 保留整列 Java 映射的 interval 表达式 |
| equalTo / lessThan | 原生比较，返回可投影或通过 eq(true) 加入 WHERE 的 Boolean 表达式 |
| addToDate / subtractFromDate | 输入 LocalDate，返回 LocalDateTime |
| addToDateTime / subtractFromDateTime | 输入并返回 LocalDateTime |

```java
query.updatable(Event.class).setColumns(e -> {
    var pg = PgSqlDsl.of(e);
    e.span().set(pg.view(e.span(), PgSqlTypes.interval())
            .plus(pg.value(PgInterval.of(0, 2, 3), PgSqlTypes.interval())));
}).where(e -> e.id().eq(eventId)).executeRows();
```

PgInterval.Finite 的 Java equals 比较保存的三个分量；数据库原生比较可能把 1 month 与 30 days 视作相等，而它们加到 2024-01-31 分别得到 2024-02-29 和 2024-03-01。这不是同一种相等性，不能用 Java equals 代替数据库条件。

SQL NULL 使用 Java null，infinites 使用 positiveInfinity/negativeInfinity。有限值的两个全部极值组合在服务器中保留为 infinity，因此有限值构造会拒绝这些哨兵组合。微秒负值和 Long.MIN_VALUE 采用精确整数运算，不经过 double。

只有月份和天数都为零的 Finite 才能 toFixedDuration；其他情况明确拒绝。SQL 层的无限值相减、取反溢出等错误保留数据库异常，不替换为 NULL 或默认零。

## 四种输出格式与驱动边界

pgJDBC 的 PGInterval 对超大合法小时值会溢出，并且 sql_standard 格式存在静默丢字段问题。因此读取经 ResultSet.getString，使用针对 PostgreSQL 服务器四种输出格式的精确解码器；该解码器不接受任意人类输入语法。写入仍使用 pgJDBC PGobject 和原 PreparedStatement，三个分量带显式符号，避免 SQL standard 的首个负号向后传播。

强制开启 interval 的 binaryTransferEnable 时，42.7.14 的 getString 对非 NULL 值也会返回 null。handler 通过 wasNull 区分真正的 SQL NULL，必要时读取公开 getBytes 返回的 16 字节数据，分别还原微秒、天、月与两个无限哨兵；不会静默丢弃有效值。

不会修改业务连接的 IntervalStyle。实库验证覆盖 postgres、postgres_verbose、sql_standard、iso_8601，普通与强制服务端预处理、显式二进制传输，各分量极值、混合符号、NULL 和无限值；日期月底、实体更新、回滚和 SQL 算术错误另有 DSL 用例。

验证入口：PgIntervalCodecTest、PostgresIntervalMappingIT、PostgresIntervalDslIT、DslCompilationTest。完整时区类型目录、组合与性能验收仍按 G4/G6 继续推进。

依据：[PostgreSQL 17 interval 表示与输出](https://www.postgresql.org/docs/17/datatype-datetime.html#DATATYPE-INTERVAL-OUTPUT)、[pgJDBC SQL standard 字段丢失问题](https://github.com/pgjdbc/pgjdbc/issues/4294)、[pgJDBC 超大小时读取问题](https://github.com/pgjdbc/pgjdbc/issues/4301)。
