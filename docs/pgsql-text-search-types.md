# PostgreSQL 全文值类型

`PgTsVector` 和 `PgTsQuery` 分别表示 PostgreSQL `tsvector`、`tsquery` 的编码值。它们保持两个不同的 Java 类型，配合 `PgSqlTypes.tsvector()`、`PgSqlTypes.tsquery()` 提供 DDL、typed value 和真实 CAST。

## 安装与实体映射

client 使用上游 `PgSQLDatabaseConfiguration` 和 `PgSqlExtensions::configure`。构建后、首次实体元数据加载之前，沿现有入口注册类型处理器：

```java
PgSqlExtensions.configureTypeHandlers(
        query.getEasyQueryClient().getRuntimeContext().getJdbcTypeHandlerManager());
```

普通类型和编码值读写无需 query-scope 工厂。两个字段可以直接声明：

```java
@Column(dbType = "tsvector")
private PgTsVector document;

@Column(dbType = "tsquery")
private PgTsQuery condition;
```

需要显式 handler 类名时，分别使用 `PgTextSearchTypeHandlers.Vector.class` 和 `PgTextSearchTypeHandlers.Query.class`；运行时类不同，按类名查找不会因泛型擦除误选另一种类型。注册使用 `appendHandler(..., false)`，不覆盖应用已注册的类型处理器。

`PgScalarMetadataManager` 为两个类型提供基础标量元数据，`selectColumn` 和原生 `sqlQuery(..., PgTsVector.class)` 可以返回值对象，不把 record 当成普通实体构造。使用自定义 metadata manager 的应用应保留这一分类。

## 编码值与普通文本

```java
var vector = new PgTsVector("'coffee':1A 'tea':2");
var condition = new PgTsQuery("'coffee' & 'tea'");

var result = query.queryable(Document.class).select(row -> Select.DRAFT.of(
        PgSqlDsl.of(row).value(vector, PgSqlTypes.tsvector()),
        PgSqlDsl.of(row).value(condition, PgSqlTypes.tsquery()),
        PgSqlDsl.of(row).nativeCast(row.encodedVector(), PgSqlTypes.tsvector())));
```

返回值是 `AnyTypeExpression<PgTsVector>` 或 `AnyTypeExpression<PgTsQuery>`，继续参与上游投影、比较和查询组合。编译器会拒绝将 query 值传给 vector 类型描述、把普通 String 当作 typed value，或把 vector CAST 结果赋给 query 表达式。

编码值遵循 PostgreSQL 输入语法：

- `PgTsVector` 的文本可以包含词素、位置和 A/B/C/D 权重。
- `PgTsQuery` 的文本可以包含词素与查询运算符；Java 层只验证非 NULL 和可传输 Unicode，语法由数据库校验。
- 空编码是合法的空 vector/query；SQL NULL 使用 Java `null`，两者不会混淆。
- 原文分词、停用词、词干和词典配置属于 `to_tsvector`、`plainto_tsquery`、`websearch_to_tsquery` 等数据库操作。

数据库可能改变编码表示。例如 `fat:2B,4C a:1A cat:5D` 回读为 `'a':1A 'cat':5 'fat':2B,4C`。Java record 的 equals 比较当前编码字符串，不执行数据库规范化，也不表示逻辑查询等价。PostgreSQL 还会按其规则处理位置范围，包括将大于 16383 的位置归到上限；本包装器不复制解析器，因此不保证任意输入编码逐字节往返。规则见 [PostgreSQL 17 全文数据类型](https://www.postgresql.org/docs/17/datatype-textsearch.html)。

## JDBC 与缓存读取

参数使用受控类型名的 PGobject，通过 `PreparedStatement.setObject` 携带 native 类型 OID，编码内容不进入 SQL 文本。读取严格校验 JDBC 类型名，再从 PGobject 提取编码；已有文本缓存也可以读取。错误的 SQL 类型不会被悄悄当作 vector/query。

分片在连接受限模式下会缓存 `getObject()` 得到的 PGobject，上游内存结果集的 `getString()` 会直接强转 String。因此 handler 自己读取无类型对象，避免缓存后的 ClassCastException；没有扩展执行器或时间结果集包装器。

当前 pgJDBC 42.7.14 对这两个类型默认使用文本传输，客户端和服务端预编译均已验证。若显式强制 `binaryTransferEnable=tsvector,tsquery` 而没有可读取编码的 binary codec，驱动可能产生一个非 NULL 但文本为 null 的 PGobject。handler 会抛出明确 SQLException，保留数据错误；真实 SQL NULL 仍正常返回 null。无需关闭其他受支持类型的二进制传输。依据为锁定驱动源码的 PGobject 读取链及 [pgJDBC 服务端预编译与类型](https://jdbc.postgresql.org/documentation/server-prepare/)。

## 已验证范围

- 两种 prepareThreshold 模式的实体插入、更新、筛选、scalar、Draft、CTE、typed value 和 CAST。
- 词素排序、权重及默认权重表示、空编码、SQL NULL、引号和中文绑定。
- PostgreSQL 语法错误与错误 JDBC 类型的明确失败。
- 独立 handler 类名和基本标量 metadata；消费者编译负例。
- 模拟 PGobject 缓存的红绿回归，以及两分表在 `useShardingConfigure(1, CONNECTION_STRICTLY)` 下的真实物化读取。只设置模式但仍为一个执行组不足以证明走过缓存。
- 强制不支持的 binary transfer 不会静默变成 SQL NULL。

本批不扩展上游分片聚合算法。分组同时投影非分组的 Pro 常量时，上游可能拒绝 `ScalarColumn`；此组合没有被本次读取修复改为支持。分片全文评分也需另行证明查询语义。具体证据见 [执行记录](execution.md)；GIN 检索、query 构造与 rank 的入口见 [PG 全文查询](pgsql-fulltext.md)，仍保留单库查询边界。
