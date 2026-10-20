# HANA 受限 LOB 视图

`HanaTypes.clob()`、`nclob()`、`text()`、`blob()` 保留物理存储类型。CLOB/NCLOB/BLOB 可用于显式 value/nativeCast；TEXT 仅表示存储与索引选择，不提供伪造的 CAST AS TEXT。

```java
var hana = HanaDsl.of(document);
var content = hana.view(document.content(), HanaTypes.nclob());
var length = content.utf16Length();
var prefix = content.sliceUtf16(1, 100);
var absent = content.isSqlNull();
```

view 不改变 SQL 类型，保留原列的 Java 读取和 ValueConverter。已知列声明冲突会在构造时拒绝；nativeCast 则通过 TO_CLOB/TO_NCLOB/TO_BLOB 真实改变 SQL 表示。String/byte[] 是脱离连接的普通值；java.sql.Clob/Blob 仍需遵守 [LOB 生命周期](hana-types.md)，视图不会把 locator 自动变成可跨事务保存的对象。

## 操作和单位

| 类型 | 操作 | 结果 |
| --- | --- | --- |
| CLOB/NCLOB/TEXT | isSqlNull | 二值 Boolean 表达式 |
| CLOB/NCLOB/TEXT | utf16Length | Long；NCLOB/TEXT 按 UTF-16 单元计数，CLOB 使用 ASCII 契约 |
| CLOB/NCLOB/TEXT | sliceUtf16 | 一基起点，0–5000 个单元，返回标量字符串 |
| CLOB/NCLOB/TEXT | asText | 显式 TO_NVARCHAR 标量转换，完整保留长文本 |
| BLOB | isSqlNull / byteLength | Boolean / Long，长度单位为字节 |
| BLOB | sliceBytes | 一基起点，0–5000 字节，返回 byte[] 对应的 VARBINARY 标量 |

SQL NULL 的长度和切片保持 NULL，空对象保持与 NULL 的区别。文本切片遵循 HANA 原生 UTF-16 索引：`中😀a` 的长度是 4，取第 2 个单元、长度 1 会得到单个高代理项；需要完整字符时由调用者选对边界。接口名不暗示按 Unicode code point 或用户感知字符切片。

实测 TO_NVARCHAR(NCLOB) 可保留超过 5000 单元的文本并参与排序；这里不会额外插入 NVARCHAR(5000) CAST 导致截短。需要有界 native CAST 时可以继续使用 ProDsl.nativeCast 与 CommonTypes.unicodeText。BLOB 直接 CAST 为 VARBINARY 在目标 HANA 上报 266，字节切片使用原生 SUBSTR，其返回 VARBINARY，不走该不合法转换。

## 查询限制

LOB 视图不暴露普通字符串运算和相等比较。通用 NULL-safe 比较也会拒绝带限制的完整 LOB，NULL 检查使用 isSqlNull，标量比较先显式转换。

EasyQuery 基础选择接口继承了 asc/desc，无法仅靠缩窄返回接口删除它们；HANA LOB 实现在构造排序时拒绝实际启用的操作。GROUP BY 与 SELECT DISTINCT 则通过公开 SQL segment factory 保留的 LOB 返回标记检查，克隆后的查询也保留标记。标量转换只保留其自身返回类型，不因操作数来自 LOB 而错误拒绝。

这些检查针对已明确标注类型的表达式；未标注的旧列接口、任意原生 SQL 及进一步的关系组合仍需数据库校验，并继续纳入完整 G4.6 组合验收。没有通过扫描已生成 SQL 文本、反射私有字段或新增查询执行器推断类型。

## 参数和 Java 表示

CLOB 的 typed value 要求 ASCII；Unicode 文本选择 NCLOB。文本参数复用公共 Unicode 检查，拒绝 NUL 和未配对代理项。BLOB 参数在构造时复制输入数组，后续修改调用者的数组不会改动已构造的值表达式。

3.2.17 的 APT 会把 primitive byte[] 的代理泛型生成为 Byte[]。保留实体的 byte[] 契约时，使用已有 API 明确校正：

```java
var bytes = HanaDsl.of(d).view(d.data().asAnyType(byte[].class), HanaTypes.blob());
var prefix = bytes.sliceBytes(1, 128);
```

新生成的字节标量通过公开 metadata SPI 补入 HANA 的基本类型列表，复用现有 byte[] handler；不自动把 SQL 数组、Byte[] 和 BLOB 混为一类。

验证入口：HanaLobDslTest、HanaLobDslIT、HanaLobIT、DslCompilationTest。覆盖 CLOB/NCLOB/TEXT/BLOB 存储、长文本与 UTF-16、字节切片、NULL/空对象、参数快照、标量转换、Draft/克隆、查询限制，以及原有流式/失败清理回归。

原生依据：[SAP HANA 2.0 SPS 08 SQL Reference](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)。单位、转换结果类型及长度超过 5000 的行为以本项目目标版本的 JDBC/SQL 实测为准。
