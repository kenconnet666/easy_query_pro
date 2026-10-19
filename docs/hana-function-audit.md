# HANA 上游函数审计

基线：EasyQuery 3.2.17，SQLFuncImpl 的 67 个不同公开工厂方法。SQLFunc 接口中的便捷重载最终依赖这些实现；窗口接口和高级函数还需单独审计。

已覆盖表示翻译路径已加入，不表示该函数的全部边界已经验收。当前证据包括 HanaScalarFunctionsIT、HanaDateTimeFunctionsIT、HanaCastFunctionsIT、HanaJsonFunctionsIT、ExpressionInteropTest 和执行记录中的 MCP SELECT。部署及边界语义见 [函数契约](hana-functions.md)。其余方法未通过前，G2.2/H08 不关闭。

| 方法 | 当前状态 |
| --- | --- |
| abs | 待完成语义审计及真实执行；当前仍继承上游默认 |
| anySQLFunction | 待完成语义审计及真实执行；当前仍继承上游默认 |
| avg | 待完成语义审计及真实执行；当前仍继承上游默认 |
| avgOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| bank | 待完成语义审计及真实执行；当前仍继承上游默认 |
| booleanConstantSQLFunction | 待完成语义审计及真实执行；当前仍继承上游默认 |
| booleanSQLFunction | 待完成语义审计及真实执行；当前仍继承上游默认 |
| cast | 已覆盖基础 Java 类型及长文本、Byte 范围；HanaCastFunctionsIT |
| concat | 已覆盖 HANA 差异；见 HanaSQLFunc |
| constValue | 待完成语义审计及真实执行；当前仍继承上游默认 |
| count | 待完成语义审计及真实执行；当前仍继承上游默认 |
| countOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| dateTimeFormat | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| dateTimeProperty | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| dateTimeSQLFormat | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| denseRankNumberOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| duration | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| duration2 | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| empty | 待完成语义审计及真实执行；当前仍继承上游默认 |
| equalsWith | 待完成语义审计及真实执行；当前仍继承上游默认 |
| exists | 待完成语义审计及真实执行；当前仍继承上游默认 |
| indexOf | 已覆盖 HANA 差异；见 HanaSQLFunc |
| joining | 待完成语义审计及真实执行；当前仍继承上游默认 |
| jsonArrayByIndex | 已覆盖标量/片段/NULL/路径与容量；HanaJsonFunctionsIT |
| jsonArrayExtractByIndex | 已覆盖标量/片段/NULL/路径与容量；HanaJsonFunctionsIT |
| jsonArrayLength | 已实现，要求显式部署 HanaJsonSupport 函数；HanaJsonFunctionsIT |
| jsonObjectContainsKey | 已覆盖标量/片段/NULL/路径与容量；HanaJsonFunctionsIT |
| jsonObjectExtract | 已覆盖标量/片段/NULL/路径与容量；HanaJsonFunctionsIT |
| jsonObjectField | 已覆盖标量/片段/NULL/路径与容量；HanaJsonFunctionsIT |
| leftPad | 待完成语义审计及真实执行；当前仍继承上游默认 |
| length | 已覆盖 HANA 差异；见 HanaSQLFunc |
| like | 已覆盖 HANA 差异；见 HanaSQLFunc |
| math | 已覆盖 HANA 差异；见 HanaSQLFunc |
| max | 待完成语义审计及真实执行；当前仍继承上游默认 |
| maxColumns | 待完成语义审计及真实执行；当前仍继承上游默认 |
| maxOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| min | 待完成语义审计及真实执行；当前仍继承上游默认 |
| minColumns | 待完成语义审计及真实执行；当前仍继承上游默认 |
| minOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| not | 待完成语义审计及真实执行；当前仍继承上游默认 |
| notBank | 待完成语义审计及真实执行；当前仍继承上游默认 |
| notEmpty | 待完成语义审计及真实执行；当前仍继承上游默认 |
| now | 已覆盖 HANA 差异；见 HanaSQLFunc |
| nullOrDefault | 已覆盖 HANA 差异；见 HanaSQLFunc |
| numberCalc | 待完成语义审计及真实执行；当前仍继承上游默认 |
| orderByNullsMode | 待完成语义审计及真实执行；当前仍继承上游默认 |
| plusDateTime | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| plusDateTime2 | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| plusDateTimeMonths | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| plusDateTimeYears | 已覆盖 HANA 差异；HanaDateTimeFunctionsIT |
| random | 已覆盖 HANA 差异；见 HanaSQLFunc |
| rankNumberOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| replace | 待完成语义审计及真实执行；当前仍继承上游默认 |
| rightPad | 待完成语义审计及真实执行；当前仍继承上游默认 |
| round | 待完成语义审计及真实执行；当前仍继承上游默认 |
| rowNumberOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| stringCompareTo | 待完成语义审计及真实执行；当前仍继承上游默认 |
| subQueryValue | 待完成语义审计及真实执行；当前仍继承上游默认 |
| subString | 已覆盖 Unicode 码点边界；HanaScalarFunctionsIT |
| sum | 待完成语义审计及真实执行；当前仍继承上游默认 |
| sumOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| toLower | 待完成语义审计及真实执行；当前仍继承上游默认 |
| toUpper | 待完成语义审计及真实执行；当前仍继承上游默认 |
| trim | 待完成语义审计及真实执行；当前仍继承上游默认 |
| trimEnd | 待完成语义审计及真实执行；当前仍继承上游默认 |
| trimStart | 待完成语义审计及真实执行；当前仍继承上游默认 |
| utcNow | 已覆盖 HANA 差异；见 HanaSQLFunc |
