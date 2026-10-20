# PostgreSQL JSON 与 JSONB

`PgSqlTypes.json()` 与 `jsonb()` 是不同的静态描述。`PgSqlDsl` 返回对应视图：JSON 提供公共读取与显式 toJsonb；JSONB 额外提供包含、key/jsonpath 判断及修改。不会因 Java 字段同为 String 就自动把 JSON 当成 JSONB。

```java
var jsonb = PgSqlTypes.jsonb();
query.queryable(Document.class).where(d ->
        PgSqlDsl.of(d).view(d.content(), jsonb)
                .containsJson(JsonInput.encoded("{\"enabled\":true}")).eq(true));

query.updatable(Document.class).setColumns(d ->
        d.content().set(PgSqlDsl.of(d).view(d.content(), jsonb)
                .setPath(List.of("profile", "name"), JsonInput.encoded("\"new name\""), true)))
        .where(d -> d.id().eq(documentId)).executeRows();
```

`view` 不生成 CAST，保留源列的 Java 值类型与完整结果映射。已知 JSON/JSONB 或其他内置类型声明冲突时拒绝；计算表达式、未声明物理类型和未知 domain 是调用者的类型断言，不会暗中连接数据库核查 schema。`nativeCast` 明确生成 SQL 转换并返回编码文本视图；JSON 的 `toJsonb` 则保留原 Java 映射，允许接着调用 JSONB 方法。

## 列写入与业务转换器

首先安装增强服务，并在首次使用实体元数据前注册列 SQL 转换：

```java
var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
        .setDefaultDataSource(dataSource)
        .useDatabaseConfigure(new PgSQLDatabaseConfiguration())
        .customConfigure(PgSqlExtensions::configure)
        .build();
PgSqlExtensions.configureMappings(client.getRuntimeContext().getQueryConfiguration());
```

String JSON 列可声明为：

```java
@Column(dbType = "JSONB", sqlConversion = PgJsonbColumnConverter.class)
private String content;
```

JSON 使用 PgJsonColumnConverter；JSONB 使用 PgJsonbColumnConverter。实体插入/更新和普通值比较通过上游 ColumnValueSQLConverter 给参数增加 CAST，读取和属性引用保留原列。

EasyQuery 3.2.17 在写入时按转换后的 Java 值类型选 handler，不能只给列指定自定义 JdbcTypeHandler 就认为字符串会按 JSONB 绑定。因此这里使用公开的列 SQL 转换 SPI，没有全局替换 String handler，也不依赖驱动 PGobject。已有 ValueConverter 仍负责业务对象到 JSON 文本的编解码；业务对象列在需要时显式使用 StringTypeHandler 读取文本，再交给自己的转换器。

`PgJsonbExpression<T>` 的修改仍返回 T 对应的表达式，可直接传给原有 setter。真实测试验证了业务 Payload 在 SQL 内修改后的实体读取及事务回滚，SQL 修改不会把已经编码的 JSON 参数再次交给业务序列化器。

## 参数、路径和操作

JSON 参数使用 JsonInput，普通 SQL String 不会自动当作 JSON 文档：

- `JsonInput.encoded(...)` 表示已经编码的 JSON，由 PostgreSQL 检查 JSON 语法；增强包不选择应用的序列化库。
- `JsonInput.jsonNull()` 表示 JSON null。
- `JsonInput.sqlNull()` 表示 SQL NULL。传 Java null 代替 JsonInput 会被拒绝，避免含义不清。

JSONB 当前包含 `containsJson`、`containedBy`、`hasKey`、`hasAnyKey`、`hasAllKeys`、`pathExists`、`setPath`、`removeKey` 和 `removePath`。它们保留 PostgreSQL 原生规则，例如 key 操作也可检查/删除数组中的字符串，setPath 的 createMissing 只处理原生允许创建的末级项，不自动创建缺失的整条父路径。

setPath/removePath 使用原生 text[] 路径：List<String> 中的成员或下标按 JSON 结构解释，负下标可从数组尾部计数；这与公共 JsonPath 的显式 key/index 语义不同。SQL 构造的是带绑定元素的 text[]，没有 JDBC Array 资源需要额外释放。

`PgJsonPath` 则表示完整 PostgreSQL jsonpath 语言，用于 pathExists。无变量入口保留原生 `@?` 的错误抑制语义；带变量入口明确传入 JsonInput 变量对象和 silent 参数。它不把返回布尔值的 jsonpath 表达式误当作条件匹配，判断的仍是是否产生路径结果项。

JSONB 修改中的 SQL NULL 和 JSON null 不可混同。jsonb_set 接收 SQL NULL 的替换值会使结果为 SQL NULL；替换为 JSON null 必须显式使用 JsonInput.jsonNull。包含/存在等原生操作对于 SQL NULL 输入可返回 SQL NULL，而不是公共 jsonExists 的两值规则。

## 绑定和索引

所有文档、key、路径元素、jsonpath 和变量都作为参数传递。包含与 key/jsonpath 判断保留 PostgreSQL operator 节点。pgJDBC 需要把运算符中的 `?` 写成 `??`，所以 toSQL 的 JDBC SQL 会显示 `??`、`??|`、`??&`、`@??`；这不是额外参数。

本地 EXPLAIN 验证了包含、key 和无变量 jsonpath 判断可使用 GIN 索引。该测试临时关闭单连接的顺序扫描，只证明索引可用，不作为性能提升、真实数据成本或长期基准结论。完整性能及跨分片组合仍继续验收。

验证入口：PgJsonDslTest、PostgresJsonDslIT、DslCompilationTest。覆盖静态 API 区分、已知声明冲突、显式转换、编码参数、实体/业务值对象写入、SQL NULL/JSON null、原生修改、克隆、事务回滚及 GIN 可用性。

原生依据：[PostgreSQL 17 JSON 操作](https://www.postgresql.org/docs/17/functions-json.html)、[pgJDBC 的问号转义规则](https://jdbc.postgresql.org/documentation/query/)。
