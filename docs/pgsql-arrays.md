# PostgreSQL 一维 SQL 数组

`PgSqlTypes.array(elementClass)` 选择元素类型和 SQL ARRAY 描述。支持 String/text、Integer/int4、Long/int8、BigDecimal/numeric、Boolean/bool、UUID/uuid、LocalDate/date、LocalDateTime/timestamp 八种元素类型。一维数组和下界 1 是当前 Java 映射的明确边界；多维或其他下界在结果读取时拒绝，避免丢失原始索引含义。

## 安装与存储

使用 pgJDBC，并在首次使用实体前安装相应 handler：

```java
var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
        .setDefaultDataSource(dataSource)
        .useDatabaseConfigure(new PgSQLDatabaseConfiguration())
        .customConfigure(PgSqlExtensions::configure)
        .build();
PgSqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
```

驱动依赖是 optional，消费应用选择自己的兼容 pgJDBC 版本；当前验证基线为 42.7.14。绑定使用当前事务连接的 createArrayOf/setArray，结果使用 JDBC Array 的结果集读取。只注册 PgArrayValue，不把普通 List、String、byte[] 或 Object[] 改成 SQL 数组。

```java
@Column(dbType = "INTEGER[]")
private PgArrayValue<Integer> numbers;

row.setNumbers(PgSqlTypes.array(Integer.class).value(Arrays.asList(1, null, 2)));
```

PgArrayValue 包含显式元素描述和不可变的元素列表。输入列表会被快照，列表可含 null；NULL 数组使用 Java null，空列表表示空数组。数组描述的 DDL 输出交给既有迁移/建表入口，不自动修改实体 schema。

业务实体可以保持 List<E>，用已有 ValueConverter 在 List<E> 与 PgArrayValue<E> 间转换，并在列上指定 `typeHandler = PgArrayTypeHandler.class`。如 String 列表示 JSON 数组，继续用其原 JSON 映射；它不会因使用 List 就自动改成 SQL ARRAY。示例 fixture PgArrayRecord.LabelsConverter 展示了这条上游转换链。

## 表达式

```java
var integers = PgSqlTypes.array(Integer.class);
query.queryable(Document.class).where(d ->
        PgSqlDsl.of(d).view(d.numbers(), integers).containsAllValues(List.of(1, 2)).eq(true));

query.queryable(Document.class).selectColumn(d ->
        PgSqlDsl.of(d).view(d.numbers(), integers).elementAtSqlIndex(1));
```

PgArrayExpression<E,T> 分别保留 SQL 元素的 Java 类型 E 和完整列的 Java 映射 T。view 保留列映射，不生成 CAST；已声明的数组元素类型冲突在构造时拒绝。nativeCast 返回 PgArrayValue<E> 的新标量映射。value(List, type) 创建有明确类型的参数，含空值的参数不会依赖数据库猜测类型。

| 操作 | 返回及规则 |
| --- | --- |
| anyEq / anyEqValue | `value = ANY(array)`；分别接收元素表达式和 Java 值，返回 Boolean 表达式 |
| containsAll / containedBy / overlaps | `@>` / `<@` / `&&`；接收相同元素类型的数组表达式 |
| containsAllValues / containedByValues / overlapsValues | 同上，接收 List 元素参数 |
| cardinality | 总元素数；空数组为 0，NULL 数组为 SQL NULL |
| dimensionLength | 指定 SQL 维度的长度；空数组或不存在的维度为 SQL NULL，维度从 1 开始 |
| elementAtSqlIndex | 原生 SQL 下标访问；返回元素 E 的上游 AnyTypeExpression，越界返回 SQL NULL |

elementAtSqlIndex(1) 指向默认下界数组的第一个元素，不能把 Java List 的零基偏移直接传入。SQL 运算本身允许引用带非默认下界的既有数组；若投影整个数组到 PgArrayValue，读取边界会拒绝。值参数的元素类型、返回数字/布尔类型和提取后的元素类型分别保留，整列业务转换器不再作用于提取出的元素。

ANY 比较不是 NULL-safe equality：未匹配且有 NULL 元素时可能返回 SQL NULL；空数组的 ANY 为 false。包含操作按 PostgreSQL 的元素集合规则处理重复值，而非多重集计数。JSON 数组、UNNEST 和多维数组不属于此接口。

## 传输、时间与资源

pgJDBC 的 getArray/getResultSet 会丢失下界，二进制 PgArray.toString 也会重建默认下界文本。因此读取先检查驱动公开的二进制维度头或原始文本维度前缀，再读取值；不读取私有字段、不重新实现数组内容解析器。无法验证的其他 JDBC Array 实现会被明确拒绝。

日期与本地时间的参数格式化复用 pgJDBC TimestampUtils，保留其纪元、无穷值和微秒舍入规则；读取使用 JDBC 4.2 的对应 Java 类型。LocalDateTime 并不代表时区时间点，不接受 Instant 替代。

读取成功或失败都会 free Array，元素结果集按正常生命周期关闭。pgJDBC setArray 在返回前保存编码字节/文本，绑定后即可 free；批量执行验证覆盖该生命周期。这里依赖的是已核查的 pgJDBC 行为，不把立即 free 的结论推广到所有 JDBC 驱动。

EXPLAIN 已验证 containsAll/overlaps 的原生 operator 可以匹配 GIN。该用例仅为索引可用性检查，临时关闭单连接顺序扫描；不代表大数据性能结论。

验证入口：PgArrayTypeTest、PgArrayLifecycleTest、PgArrayDslTest、PostgresArrayMappingIT、PostgresArrayDslIT、DslCompilationTest。仍需与后续完整 G4.6/G6 组合和性能验收一起评估，不能把单次读写通过视为全部成熟度完成。

依据：[PostgreSQL 17 数组运算](https://www.postgresql.org/docs/17/functions-array.html)、[pgJDBC 数组绑定](https://jdbc.postgresql.org/documentation/server-prepare/#arrays)、[pgJDBC 42.7.14 PgArray 源码](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.14/pgjdbc/src/main/java/org/postgresql/jdbc/PgArray.java)。
