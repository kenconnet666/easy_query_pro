# HANA 上游函数审计

基线：EasyQuery 3.2.17，SQLFuncImpl 的 67 个不同公开工厂方法。SQLFunc 接口中的便捷重载最终依赖这些实现；已有窗口接口的基础分区/排序已执行；新增 ROWS/RANGE 属于独立 DSL 工作。

已覆盖表示翻译路径已加入，不表示该函数的全部边界已经验收。当前证据包括 HanaScalarFunctionsIT、HanaDateTimeFunctionsIT、HanaCastFunctionsIT、HanaJsonFunctionsIT、HanaAggregateFunctionsIT、HanaQueryCompositionIT、ExpressionInteropTest 和执行记录中的 MCP SELECT。部署及边界语义见 [函数契约](hana-functions.md)。其余方法未通过前，G2.2/H08 不关闭。

| 方法 | 当前状态 |
| --- | --- |
| abs | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| anySQLFunction | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| avg | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| avgOver | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| bank | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| booleanConstantSQLFunction | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| booleanSQLFunction | 已补谓词到布尔投影的三值 CASE；HanaScalarFunctionsIT |
| cast | 已覆盖基础 Java 类型及长文本、Byte 范围；HanaCastFunctionsIT |
| concat | 已覆盖 HANA 差异；见 HanaSQLFunc |
| constValue | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| count | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| countOver | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| dateTimeFormat | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| dateTimeProperty | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| dateTimeSQLFormat | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| denseRankNumberOver | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| duration | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| duration2 | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| empty | 已补尾随空格语义，按长度判空；HanaScalarFunctionsIT |
| equalsWith | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| exists | 相关 EXISTS 查询已执行；SQLFunc 独立工厂仍需组合验证 |
| indexOf | 已覆盖 HANA 差异；见 HanaSQLFunc |
| joining | 普通/有序/NULL 聚合已覆盖；distinct 仍需查询级去重，当前明确拒绝 |
| jsonArrayByIndex | 已覆盖标量/片段/NULL/路径与容量；HanaJsonFunctionsIT |
| jsonArrayExtractByIndex | 已覆盖标量/片段/NULL/路径与容量；HanaJsonFunctionsIT |
| jsonArrayLength | 已实现，要求显式部署 HanaJsonSupport 函数；HanaJsonFunctionsIT |
| jsonObjectContainsKey | 已覆盖标量/片段/NULL/路径与容量；HanaJsonFunctionsIT |
| jsonObjectExtract | 已覆盖标量/片段/NULL/路径与容量；HanaJsonFunctionsIT |
| jsonObjectField | 已覆盖标量/片段/NULL/路径与容量；HanaJsonFunctionsIT |
| leftPad | 已按码点补齐及截取；HanaScalarFunctionsIT |
| length | 已覆盖 HANA 差异；见 HanaSQLFunc |
| like | 已覆盖 HANA 差异；见 HanaSQLFunc |
| math | 已覆盖 HANA 差异；见 HanaSQLFunc |
| max | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| maxColumns | 已覆盖忽略 NULL 和多行关联；HanaAggregateFunctionsIT |
| maxOver | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| min | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| minColumns | 已覆盖忽略 NULL 和多行关联；HanaAggregateFunctionsIT |
| minOver | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| not | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| notBank | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| notEmpty | 已补尾随空格语义，按长度判空；HanaScalarFunctionsIT |
| now | 已覆盖 HANA 差异；见 HanaSQLFunc |
| nullOrDefault | 已覆盖 HANA 差异；见 HanaSQLFunc |
| numberCalc | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| orderByNullsMode | 复用上游实现，真实执行通过；HanaQueryCompositionIT |
| plusDateTime | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| plusDateTime2 | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| plusDateTimeMonths | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| plusDateTimeYears | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| random | 已覆盖 HANA 差异；见 HanaSQLFunc |
| rankNumberOver | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| replace | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| rightPad | 已按码点补齐及截取；HanaScalarFunctionsIT |
| round | 数学入口的 rounding 已验证；旧 TableOwner 工厂仍需单独执行 |
| rowNumberOver | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| stringCompareTo | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| subQueryValue | 标量相关查询已执行；SQLFunc 独立工厂仍需组合验证 |
| subString | 已覆盖 Unicode 码点边界；HanaScalarFunctionsIT |
| sum | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| sumOver | 复用上游实现，真实执行通过；HanaAggregateFunctionsIT |
| toLower | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| toUpper | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| trim | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| trimEnd | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| trimStart | 复用上游实现，真实执行通过；HanaScalarFunctionsIT |
| utcNow | 已覆盖 HANA 差异；见 HanaSQLFunc |
