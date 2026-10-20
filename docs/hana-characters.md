# HANA 字符类型与存储边界

`HanaTypes.varchar(n)`、`nvarchar(n)` 和 `alphanum(n)` 返回 `TextType`，使用通用 `ProDsl.value/nullValue/nativeCast` 和 EasyQuery 原有字符串表达式。`shortText(n)` 只返回 `StorageType<String>`，用于 COLUMN TABLE 的 DDL；不能传给 CAST/value。业务实体仍以 String 保存查询结果，无需为每种数据库字符列引入包装对象。

| 类型 | typed value 容量 | 行为 |
| --- | --- | --- |
| VARCHAR(n) | 1–5000，7-bit ASCII；此时字节数等于 UTF-16 单元数 | 非 ASCII 输入拒绝，Unicode 使用 NVARCHAR |
| NVARCHAR(n) | 1–5000 个 UTF-16 单元 | 完整保留输入，不截断、不规范化 |
| ALPHANUM(n) | 1–127 个 UTF-16 单元 | 保留原生数字样式比较；纯数字 typed value 必须已经补齐 n 位 |
| SHORTTEXT(n) | DDL 长度 1–5000 | 列存储及自动文本索引属性，不提供虚构的 CAST 目标 |

所有 typed value 均保留 SQL NULL 与空字符串，拒绝未配对代理项和 NUL；普通实体写入不额外施加这些 Java 校验。跨方言使用类型描述会失败。共享需求仍优先使用 `CommonTypes.unicodeText(n)`，选择 HANA 专属类型表示应用主动依赖了该物理类型。

## ALPHANUM 的补零是显式原生转换

HANA 将 ALPHANUM(6) 中的 `12`、`0012` 读取为 `000012`。`12 `、` 12`、`-1`、`a!` 保持原字符串，不按纯数字补零。查询排序时 `000002` 小于 `000012`。增强包不在 Java 中自动 trim、pad 或 strip，以免改变业务代码值。

`value` 的无损字符串契约要求调用方提供最终表示；需要 HANA 补零时使用 `nativeCast`：

```java
var code = HanaTypes.alphanum(6);
query.queryable(Product.class).selectColumn(p ->
        ProDsl.of(p).value("000012", code));
query.queryable(Product.class).selectColumn(p -> {
    var d = ProDsl.of(p);
    return d.nativeCast(d.value("12", CommonTypes.unicodeText(10)), code);
});
```

第一条返回 `000012`；第二条显式请求原生转换，也返回 `000012`。`value("12", code)` 在 JDBC 执行前失败。CAST 的截断和比较仍遵循数据库语义。

## SHORTTEXT 和 Code First

SHORTTEXT 可用于 COLUMN TABLE，读取继续走 String，普通字符串函数、排序、DISTINCT、NULL 谓词复用上游能力。目标数据库拒绝 `CAST(... AS SHORTTEXT(n))`，因此描述只暴露 DDL。全文模式与评分属于查询级能力，后续全文 DSL 负责这些操作，SHORTTEXT 描述本身不执行查询或创建额外索引。

HANA 目录能识别 SHORTTEXT 及其长度。Code First 实库用例包含 TINYINT、SMALLDECIMAL、无 p/s 的浮点 DECIMAL、DECIMAL(38,38)、ALPHANUM、SHORTTEXT、VARCHAR/NVARCHAR，第二次生成的迁移计划为空。浮点 DECIMAL 的目录 scale 为 NULL，不能误改成固定小数；SHORTTEXT 生成的全文索引保持存在。

行为以 HANA 2.0 revision 88、ngdbc 2.30.11 为本地证据。官方规则见 [HANA Platform 字符类型](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/a33f7884b0c14c00b1a76ecd8af5feca.html)。ALPHANUM 是当前本地部署目标的能力；[HANA Cloud 的迁移说明](https://help.sap.com/docs/hana-cloud/sap-hana-cloud-migration-guide/alphanum-data-type) 明确其不适用，不能将本地结果直接宣传为 Cloud 支持。

`HanaCharacterDslTest` 与消费者编译负例覆盖范围和类型边界；`HanaCharacterIT` 覆盖 Unicode 上限、实际批量存储、原生补零、绑定、NULL、clone、普通字符串查询与回滚；`HanaCodeFirstIT` 验证完整类型目录和再次迁移。
