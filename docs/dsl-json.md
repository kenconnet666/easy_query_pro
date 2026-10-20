# 结构化 JSON 路径

公共 JSON 路径是不可变的单值导航，支持字面成员名和非负零基数组下标，最多 100 段。它不是接收任意 SQL/JSON 或 PostgreSQL jsonpath 字符串的入口；通配符、筛选语法和负下标不隐式混入公共契约。

```java
var path = JsonPath.root().key("items").index(0).key("name");
query.queryable(Document.class).selectColumn(d ->
        ProDsl.of(d).jsonTextAt(d.content(), path));
query.queryable(Document.class).where(d ->
        ProDsl.of(d).jsonExists(d.content(), path).eq(true));
```

`key("a.b")` 表示名字为 a.b 的一个成员；`key("0")` 与 `index(0)` 不相同。空键、Unicode、引号、反斜线、换行和花括号按数据处理。非法 UTF-16、NUL 键和负下标在构造路径时拒绝。路径对象可共享，返回的查询表达式仍属于当前查询和 client。

## 输出类型与 NULL

| 入口 | 返回表达式 | 含义 |
| --- | --- | --- |
| jsonAt(document, path) | JsonFragmentExpression | 保留选中 JSON 值；投影读取编码后的 JSON 文本 |
| jsonAt(...).asText() | StringTypeExpression<String> | 显式将 JSON 值序列化为 SQL 文本，之后可做普通字符串操作 |
| jsonTextAt(document, path) | StringTypeExpression<String> | 解码标量文本；对象/数组返回 SQL NULL |
| jsonBooleanAt(document, path) | BooleanTypeExpression<Boolean> | 只读取 JSON true/false；字符串 "true" 不冒充布尔值 |
| jsonExists(document, path) | BooleanTypeExpression<Boolean> | 路径存在返回 true，SQL NULL/缺失/结构不匹配返回 false |

JSON null 对应的片段是文本 `null`，存在性为 true；解码标量和布尔结果为 SQL NULL。JSON 字符串 `"null"` 解码后是普通字符串 null，两者不混同。数字的文本形式按数据库 JSON 表示规则生成，不保证保留原始数字字面量的空白、指数拼写或尾零；需要数字运算时显式使用既有数字转换。

`JsonFragmentExpression` 的 Java 投影类型虽然是 String，但 SQL 值仍属于 JSON，不直接开放 toUpper/concat 等文本方法。使用 asText 后才进行文本操作；公共 NULL-safe 比较也拒绝把未转换的片段当成普通文本。原生 JSON/JSONB 包含、修改和比较仍属于对应方言模块。

导航按结构匹配：对对象使用 index、对数组使用 key、或在标量上继续导航都视为缺失。不会因数据库的自动数组包装/展开而选中不同的数据。输入应为编码 JSON 文本或兼容的原生 JSON 列/表达式；它不是将任意 Java bean 自动序列化为参数的入口。已有字段的 SQL 属性转换仍通过上游协议应用，部分结果不再次进入整列 ValueConverter。

## 方言实现

- MySQL 使用绑定路径与前缀类型检查，关闭隐式数组包装造成的语义差异。文档和路径显式转换为 utf8mb4：Connector/J 在 NO_BACKSLASH_ESCAPES + 客户端预处理下可能使用 binary 十六进制参数，直接交给 JSON 函数会出错。已同时验证客户端/服务器端预处理，不更改应用会话设置。
- PostgreSQL 读取使用 JSON 与带类型的 `-> text` / `-> integer`。这样 key("0") 不变成数组下标，并避免 JSONB 标量在原生 `-> 0` 下被当作元素零。不会把 JSON 来源偷偷改成 JSONB；片段的编码形式不承诺字节级不变。
- HANA 复用现有 JSON_QUERY/JSON_VALUE 及其转义、容量配置。原生路径必须是 SQL 常量，使用已有 format 参数处理转义后的字面量；不会把键内的 `{0}` 当模板。下面的小型辅助函数检查文档和节点类型，防止隐式展开及标量根文档解析差异。

MySQL 的前缀检查会重复引用根表达式；HANA 对各级节点执行原生解析和类型过滤。输入应为稳定的列或确定性表达式，不能据此承诺对随机/有副作用表达式只求值一次。它们不把整行拉到 JVM，也不为每个路径段增加数据库网络查询；完整性能与分片组合仍按 G6 验收。

## HANA 的显式部署

```java
String ddl = HanaJsonSupport.createDocumentFunctionSql("APP_SUPPORT");
// Execute ddl once through the application's controlled migration process.
var options = new HanaOptions(5000, "APP_SUPPORT");
var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
        .setDefaultDataSource(dataSource)
        .useDatabaseConfigure(new HanaDatabaseConfiguration(options))
        .customConfigure(HanaExtensions::configure)
        .build();
```

创建的是 `APP_SUPPORT.EQP_JSON_DOCUMENT`，SQL SECURITY INVOKER；使用者需要相应 EXECUTE 权限。它使用 SQLScript 的 NCLOB 参数和原生 JSON_TABLE，不替代上游执行器或事务。未设置 jsonSupportSchema 时，新路径入口在 JDBC 调用前明确拒绝；安装增强、构建查询或首次执行都不会自动创建 schema/函数。

数组长度仍是独立的 `createArrayLengthFunctionSql`，原有简单 JSON getter 也不因此新增部署要求。HanaOptions 作为当前 client 的不可变服务供底层函数与增强层共享，避免出现两套容量或 schema 配置。

辅助函数按 HANA 原生 parser 检查单个 JSON 值，保留 SQL NULL，并拒绝空文档、多个顶层值和识别出的无效输入。它不是 RFC/JSON Schema 校验器；实测 HANA parser 对 `[1,]` 等非标准输入仍较宽松。跨库调用应提供合法的 Unicode JSON。原生 JSON_VALUE/JSON_QUERY 的容量及 ERROR ON ERROR 规则继续生效，中间片段也需要满足配置容量，见 [HANA JSON 说明](hana-functions.md)。

## 验证

JsonPathDslTest 与消费者 javac 用例覆盖不可变路径、类型约束、参数顺序、克隆、跨查询/client 拒绝以及缺失 HANA 配置。JsonPathDslContract 在三库执行相同的 JSON null/缺失/SQL NULL、类型不匹配、根标量、空容器、特殊键、过滤与投影用例。另有 MySQL SQL mode/预处理方式组合、PostgreSQL JSONB 根值差异和 HANA 辅助函数生命周期测试。

原生规则参考：[MySQL JSON 搜索函数](https://dev.mysql.com/doc/refman/8.4/en/json-search-functions.html)、[PostgreSQL 17 JSON 运算](https://www.postgresql.org/docs/17/functions-json.html)、[SAP HANA Platform JSON_QUERY/JSON_TABLE/JSON_VALUE](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)。HANA 路径绑定错误 257、标量根解析差异，以及 MySQL binary 参数行为均有独立原生 JDBC 探测证据。
