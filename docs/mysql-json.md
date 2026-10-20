# MySQL JSON 原生类型

`MySqlTypes.json()` 选择 `MySqlJsonExpression<T>`。通用成员读取仍通过 at/textAt/booleanAt/existsAt；MySQL 专属的包含、存在性与修改通过该视图提供。普通字符串操作需要显式 asText，SQL 修改结果保留原 Java 类型 T，可用于已有 setter。

```java
query.queryable(Document.class).where(d ->
        MySqlDsl.of(d).view(d.content(), MySqlTypes.json())
                .containsJson(JsonInput.encoded("{\"enabled\":true}")).eq(true));

query.updatable(Document.class).setColumns(d ->
        d.content().set(MySqlDsl.of(d).view(d.content(), MySqlTypes.json())
                .setPath(JsonPath.root().key("name"), JsonInput.encoded("\"new name\""))))
        .where(d -> d.id().eq(documentId)).executeRows();
```

`view` 是物理类型声明，不生成 CAST；已知非 JSON 内置列声明会被拒绝。未声明类型、未知自定义类型或计算表达式仍由调用者保证其含义，不会自动查询 schema。`nativeCast` 显式生成 CAST 并返回编码文本视图；`value` 接收已编码 JsonInput。

## 安装与实体映射

```java
var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
        .setDefaultDataSource(dataSource)
        .useDatabaseConfigure(new MySQLDatabaseConfiguration())
        .customConfigure(MySqlExtensions::configure)
        .build();
MySqlExtensions.configureMappings(client.getRuntimeContext().getQueryConfiguration());
```

在首次使用实体元数据前注册映射。需要显式 JSON 参数绑定的列使用：

```java
@Column(dbType = "JSON", sqlConversion = MySqlJsonColumnConverter.class)
private String content;
```

已有业务 ValueConverter 继续负责对象与 JSON 文本的编解码；需要以文本读取业务对象时，列可同时指定 `typeHandler = StringTypeHandler.class`。修改表达式保留整列结果映射，局部读取则返回共同的标量/JSON 片段。没有全局替换 String handler，也不会重复序列化已经编码的 JsonInput。

列转换与显式 JSON 值使用 `CAST(CONVERT(? USING utf8mb4) AS JSON)`。Connector/J 的客户端预处理在 NO_BACKSLASH_ESCAPES 下可能把字符串编码为 binary 十六进制，直接交给 JSON 解析会失败；显式字符集转换解决该绑定边界。JSON 路径参数同样转 utf8mb4；真实 JSON 列保持原列引用。

## 确定的操作与边界

| 操作 | 契约 |
| --- | --- |
| containsJson(candidate) | JSON_CONTAINS，检查整个文档 |
| containsJson(candidate, path) | 在指定目标路径检查；缺失目标返回 SQL NULL |
| containsAnyPath / containsAllPaths | JSON_CONTAINS_PATH 的 one/all；至少一个路径 |
| setPath / setPaths | JSON_SET；按调用者提供的顺序逐项修改 |
| removePath / removePaths | JSON_REMOVE；按顺序删除，禁止根路径，至少一个路径 |

setPaths 接收 MySqlJsonChange 序列，而非 Map；这允许先创建父节点再修改其子节点，也允许重复写同一路径。removePaths 删除数组元素后，后续下标针对已改变的数组。数组参数会被快照，调用后修改调用者的数组不会改变已构造的查询。

```java
var root = JsonPath.root();
var changed = mysql.view(d.content(), MySqlTypes.json()).setPaths(
        new MySqlJsonChange(root.key("profile"), JsonInput.encoded("{}")),
        new MySqlJsonChange(root.key("profile").key("name"), JsonInput.encoded("\"Ada\"")));
```

JsonPath 的 key 是字面成员名，index 是非负下标，不接受拼接路径、通配符或 SQL 片段。所有实际路径和文档均绑定为参数。路径结构共享不意味着语义相同：这些专属方法保留 MySQL 的原生数组自动包装和缺失父节点规则；共同的 existsAt/at 则继续采用严格 key/index 读取规则。

JsonInput.encoded 不解析 JSON，数据库检查语法；Java null 包装对象会被拒绝。JsonInput.jsonNull 表示 JSON null，JsonInput.sqlNull 表示 SQL NULL。MySQL JSON_SET 的替换值为 SQL NULL 时写入 JSON null；根替换也得到 JSON null。原文档为 SQL NULL 时修改结果仍为 SQL NULL。原生包含/路径存在判断对于 SQL NULL 文档返回 SQL NULL，公共 existsAt 返回 false。

上游已经提供基本 JSON 读取及单个 key 判断；本模块补足类型视图、结构化多路径、编码文档参数和选定修改，不替换既有 SQLFunc 或执行器。不会把 MySQL 原生修改伪装成与 PostgreSQL/HANA 等价的公共能力。

验证入口：MySqlJsonDslTest、MySqlJsonDslIT、DslCompilationTest。实库契约覆盖客户端/服务端预处理与普通/NO_BACKSLASH_ESCAPES 模式的四种组合，检查多语言 key、顺序修改、缺失、NULL、业务对象更新与回滚。索引、部分原地更新是否被优化器采用以及完整性能基准仍需独立验收，不能从函数名称推断。

原生依据：[MySQL 8.4 JSON 修改函数](https://dev.mysql.com/doc/refman/8.4/en/json-modification-functions.html)、[JSON 检索函数](https://dev.mysql.com/doc/refman/8.4/en/json-search-functions.html)。
