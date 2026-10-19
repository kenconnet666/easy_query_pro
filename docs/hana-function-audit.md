# HANA 上游函数审计

基线：EasyQuery 3.2.17，SQLFuncImpl 的 67 个不同公开工厂方法。SQLFunc 接口中的便捷重载最终依赖这些实现；窗口接口和高级函数还需单独审计。

已覆盖表示翻译路径已加入，不表示该函数的全部边界已经验收。当前组合证据为 HanaScalarFunctionsIT、ExpressionInteropTest 和执行记录中的 MCP SELECT。其余方法未通过前，G2.2/H08 不关闭。

| 方法 | 当前状态 |
| --- | --- |
| abs | 待完成语义审计及真实执行；当前仍继承上游默认 |
| anySQLFunction | 待完成语义审计及真实执行；当前仍继承上游默认 |
| avg | 待完成语义审计及真实执行；当前仍继承上游默认 |
| avgOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| bank | 待完成语义审计及真实执行；当前仍继承上游默认 |
| booleanConstantSQLFunction | 待完成语义审计及真实执行；当前仍继承上游默认 |
| booleanSQLFunction | 待完成语义审计及真实执行；当前仍继承上游默认 |
| cast | 待完成语义审计及真实执行；当前仍继承上游默认 |
| concat | 已覆盖 HANA 差异；见 HanaSQLFunc |
| constValue | 待完成语义审计及真实执行；当前仍继承上游默认 |
| count | 待完成语义审计及真实执行；当前仍继承上游默认 |
| countOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| dateTimeFormat | 待完成语义审计及真实执行；当前仍继承上游默认 |
| dateTimeProperty | 待完成语义审计及真实执行；当前仍继承上游默认 |
| dateTimeSQLFormat | 待完成语义审计及真实执行；当前仍继承上游默认 |
| denseRankNumberOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| duration | 待完成语义审计及真实执行；当前仍继承上游默认 |
| duration2 | 待完成语义审计及真实执行；当前仍继承上游默认 |
| empty | 待完成语义审计及真实执行；当前仍继承上游默认 |
| equalsWith | 待完成语义审计及真实执行；当前仍继承上游默认 |
| exists | 待完成语义审计及真实执行；当前仍继承上游默认 |
| indexOf | 已覆盖 HANA 差异；见 HanaSQLFunc |
| joining | 待完成语义审计及真实执行；当前仍继承上游默认 |
| jsonArrayByIndex | 待完成语义审计及真实执行；当前仍继承上游默认 |
| jsonArrayExtractByIndex | 待完成语义审计及真实执行；当前仍继承上游默认 |
| jsonArrayLength | 待完成语义审计及真实执行；当前仍继承上游默认 |
| jsonObjectContainsKey | 待完成语义审计及真实执行；当前仍继承上游默认 |
| jsonObjectExtract | 待完成语义审计及真实执行；当前仍继承上游默认 |
| jsonObjectField | 待完成语义审计及真实执行；当前仍继承上游默认 |
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
| plusDateTime | 待完成语义审计及真实执行；当前仍继承上游默认 |
| plusDateTime2 | 待完成语义审计及真实执行；当前仍继承上游默认 |
| plusDateTimeMonths | 待完成语义审计及真实执行；当前仍继承上游默认 |
| plusDateTimeYears | 待完成语义审计及真实执行；当前仍继承上游默认 |
| random | 已覆盖 HANA 差异；见 HanaSQLFunc |
| rankNumberOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| replace | 待完成语义审计及真实执行；当前仍继承上游默认 |
| rightPad | 待完成语义审计及真实执行；当前仍继承上游默认 |
| round | 待完成语义审计及真实执行；当前仍继承上游默认 |
| rowNumberOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| stringCompareTo | 待完成语义审计及真实执行；当前仍继承上游默认 |
| subQueryValue | 待完成语义审计及真实执行；当前仍继承上游默认 |
| subString | 待完成语义审计及真实执行；当前仍继承上游默认 |
| sum | 待完成语义审计及真实执行；当前仍继承上游默认 |
| sumOver | 待完成语义审计及真实执行；当前仍继承上游默认 |
| toLower | 待完成语义审计及真实执行；当前仍继承上游默认 |
| toUpper | 待完成语义审计及真实执行；当前仍继承上游默认 |
| trim | 待完成语义审计及真实执行；当前仍继承上游默认 |
| trimEnd | 待完成语义审计及真实执行；当前仍继承上游默认 |
| trimStart | 待完成语义审计及真实执行；当前仍继承上游默认 |
| utcNow | 已覆盖 HANA 差异；见 HanaSQLFunc |
