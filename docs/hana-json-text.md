# HANA JSON 文本视图

`HanaTypes.jsonNclob()` 与 `jsonNvarchar(capacity)` 是已有文本存储之上的 JSON 语义声明，DDL 分别为 NCLOB 与 NVARCHAR(n)。HANA 没有因此获得 JSONB 物理类型，也不提供 MySQL/PG 的 JSON 修改方法。

```java
var json = HanaDsl.of(d).view(d.content(), HanaTypes.jsonNclob());
var name = json.textAt(JsonPath.root().key("profile").key("name"));
var active = json.booleanAt(JsonPath.root().key("active"));
```

视图复用共同的 at/textAt/booleanAt/existsAt 及结构化 JsonPath。原有原生 JSON 函数翻译、路径转义、标量/容器区分、JSON null 与 SQL NULL 规则保持同一实现；HANA 的路径和结果容量限制见 [共同 JSON 说明](dsl-json.md)。

## 配置与部署

路径导航需要在 HanaOptions 中配置 jsonSupportSchema，并由应用部署步骤显式执行 HanaJsonSupport.createDocumentFunctionSql。库不会在第一次查询时自动创建函数或申请权限。

完整值读取与 asText 只做物理文本读取/转换，可以在没有路径辅助函数配置的 client 中使用。需要路径操作但缺少配置时，仍会在表达式构造阶段拒绝，早于 JDBC 执行。已配置 schema 但未实际部署函数则保留数据库错误。

## 类型、容量和转换

NVARCHAR 容量为 1–5000 个 UTF-16 单元，typed value 在执行前检查编码文本是否容纳得下；NCLOB 不额外添加小字符串上限。参数使用 JsonInput：encoded 表示已经编码的 JSON 文本，jsonNull 表示文本 `null`，sqlNull 表示 SQL NULL。普通 Java null 不可代替 JsonInput 包装对象。

```java
var small = HanaDsl.of(d).value(JsonInput.encoded("{\"active\":true}"), HanaTypes.jsonNvarchar(1000));
var wide = HanaDsl.of(d).nativeCast(d.smallContent(), HanaTypes.jsonNclob());
```

view 保留完整列的 Java 映射和既有 ValueConverter；已知存储声明或 NVARCHAR 容量冲突时拒绝。nativeCast 明确改变物理文本表示并返回编码 String 映射。NVARCHAR 的 nativeCast 仍可能按 HANA 规则截断，若截断破坏 JSON，后续导航会报数据库解析错误；它不是保真容量检查。

这些声明不会自动增加 JSON CHECK 约束。encoded 也不在 Java 中解析 JSON；路径导航时由现有 HANA 辅助函数与 JSON 函数检查。asText 只是转换为完整标量文本，不宣称验证 JSON 文法。目标 HANA parser 的宽松输入边界仍按共同 JSON 文档记录，不冒充 RFC 校验器。

## 查询和业务映射

NCLOB JSON 保留 LOB 返回标记，排序/分组/SELECT DISTINCT 的限制沿用 [HANA LOB 视图](hana-lob-dsl.md)。NVARCHAR JSON 在物理上是标量，可以参与这些原生 SQL 子句；通用相等比较仍要求显式 asText，避免混淆 JSON 语义与编码文本比较。

asText 使用 TO_NVARCHAR，不添加 NVARCHAR(5000) CAST。本地验证了超过 6000 个 UTF-16 单元的文档整体读取和配置较大容量后的标量路径读取。原实体的 JSON 业务 record 可通过 StringTypeHandler 与 ValueConverter 保留；Draft、单列投影和克隆不增加重复序列化。

验证入口：HanaJsonTextDslTest、HanaJsonTextDslIT、HanaJsonPathDslIT、DslCompilationTest。覆盖辅助函数配置边界、NCLOB/NVARCHAR、容量、JSON/SQL NULL、坏文本导航、Unicode 长值、业务映射和类型编译负例。

原生依据：[SAP HANA 2.0 SPS 08 SQL Reference](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)。
