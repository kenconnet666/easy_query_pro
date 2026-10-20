# 数据库端日期截断

`ProDsl.truncateDate` 接受 `LocalDate` 表达式，`truncateDateTime` 接受 `LocalDateTime` 表达式。返回原有 `DateTimeTypeExpression`，保留日期或日期时间的 Java 类型。它们在数据库内计算，可以继续参与投影、过滤、分组和排序。

```java
query.queryable(Event.class).selectColumn(e ->
        ProDsl.of(e).truncateDateTime(e.occurred(), TruncationUnit.MONTH));
query.queryable(Event.class).selectColumn(e ->
        ProDsl.of(e).truncateDate(e.eventDate(), TruncationUnit.ISO_WEEK).year());
```

| 粒度 | LocalDate | LocalDateTime |
| --- | --- | --- |
| YEAR | 当年 1 月 1 日 | 同日 00:00:00 |
| QUARTER | 所在季度的首日 | 同日 00:00:00 |
| MONTH | 当月 1 日 | 同日 00:00:00 |
| ISO_WEEK | 所在周的星期一 | 同日 00:00:00 |
| DAY | 当日 | 当日 00:00:00 |
| HOUR | 构造时拒绝 | 分、秒和小数秒归零 |
| MINUTE | 构造时拒绝 | 秒和小数秒归零 |
| SECOND | 构造时拒绝 | 小数秒归零 |

NULL 输入保留 NULL。日期输入不能请求小时粒度；时间点 `Instant` 不能直接传入这两个 API。时间点必须先明确选择日历时区，不能用 JVM 或数据库的默认时区替用户决定。`LocalDateTime` 要与无时区 SQL 列/表达式匹配，不能把错误映射的 timestamptz 当成无时区字段。

## 翻译与组合

PostgreSQL 使用 `date_trunc`，显式选择 `timestamp without time zone`，避免 DATE 的重载推断进入 timestamptz。MySQL 使用日期算术、MAKEDATE、MAKETIME 和 TIMESTAMP；HANA 使用 ADD_YEARS/ADD_MONTHS/ADD_DAYS/ADD_SECONDS。返回 SQL DATE/TIMESTAMP，没有将日期格式化成字符串后冒充日期结果。

HANA 的月/季度起点使用年月坐标计算，不能简单减去 DAYOFMONTH-1。原生复现证明，从 `1582-10-18` 减去 17 天会因历法缺口落到 `1582-09-21`；按年月构造可正确得到 `1582-10-01`。共同契约包含切换后的边界输入。

MySQL 和 HANA 的部分粒度需要多次引用输入。直接复用上游 SQLFormatArgument 的延迟渲染：模板每次引用 `{0}` 都会按 SQL 中的实际顺序生成对应参数。增强包不维护另一套参数计数、重排或模板展开器。输入应为确定性表达式；若业务需要对随机或有副作用的日期来源保证单次求值，应先取得稳定值，不能依赖优化器保留某层 SELECT。

普通 SQL 属性转换、函数嵌套、参数顺序、克隆和跨 client/查询作用域检查沿用现有 DSL 基础。跨分片非线性结果合并暂不自动放开，继续使用明确拒绝策略。

## 历法边界与当前证据

常规公历日期已通过三库共同契约，涵盖 ISO 周跨年、闰日、季度/月末、小数秒、NULL、绑定表达式重复引用、克隆及过滤。`TemporalTruncationDslTest` 检查全部粒度的 SQL 参数数量和日期粒度拒绝；`DslCompilationTest` 检查 LocalDate/LocalDateTime 类型传播以及误传 Instant 的编译失败。

**公历切换前的跨库等价性仍未关闭。** 本地独立 MCP 查询确认：`1500-02-28` 的星期在 HANA 为星期五，在 PostgreSQL 为星期三。HANA 使用的历史历法及 1582 年日期缺口见 [HANA 类型契约](hana-types.md)。当前翻译在这一区域沿用各数据库历法，不应把历史 ISO 周的结果当成跨库一致，也没有偷偷用字符串日期或错误结果掩盖差异。

因此当前共同验收范围限定在完整公历周之后，即 `1582-10-18` 及之后、同时处于所用数据库合法日期范围的输入；该范围是使用前提，当前 API 不扫描或自动检查整列数据。更早日期的统一拒绝/表示方案仍属于 G0.4/G4.3 的未关闭项，不能用本轮现代日期测试将其标为完成。

原生依据：[PostgreSQL 17 date_trunc](https://www.postgresql.org/docs/17/functions-datetime.html)、[MySQL 8.4 日期函数](https://dev.mysql.com/doc/refman/8.4/en/date-and-time-functions.html)、[HANA Platform SQL Reference](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)。日期算术与 HANA 历史星期差异还通过已配置的 IDEA 数据库 MCP 直接核对。
