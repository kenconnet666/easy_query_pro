# 时间分桶与缺月报表

时间序列报表分成两个独立问题：把事件放进正确的时间桶，以及为没有事件的桶保留一行。普通日期截断、LEFT JOIN、聚合和 LAG 已由共同 DSL 与上游查询 API 提供；本包只新增 PostgreSQL 缺失的固定步长 `date_bin`，补月部分提供三库验证的组合方式。

## PostgreSQL 固定步长

```java
PgSqlExtensions.configureTypeHandlers(
        query.getEasyQueryClient().getRuntimeContext().getJdbcTypeHandlerManager());

var origin = LocalDateTime.of(1970, 1, 1, 0, 2, 30);
var bins = query.queryable(Event.class)
        .select(event -> Select.DRAFT.of(
                PgSqlDsl.of(event).dateBin(event.occurred(), Duration.ofMinutes(15), origin)))
        .toCteAs("event_bins");
var counts = bins.groupBy(row -> GroupKeys.of(row.value1()))
        .select(group -> Select.DRAFT.of(group.key1(), group.count()));
```

`Event.occurred` 为 `LocalDateTime`，返回值继续是上游 `DateTimeTypeExpression<LocalDateTime>`，可以参与后续日期函数、筛选与投影。client 仍按 [PG 时间映射](pgsql-temporal.md) 安装 `PgSqlExtensions::configure`；上面的显式类型配置复用现有 [interval handler](pgsql-interval.md)，必须在首次相关元数据加载之前执行。

步长和 origin 均为 JDBC 参数。步长转换成不含月份或自然日部分的 `PgInterval` 微秒值，来源和 origin 显式转换为 `timestamp without time zone`。有参数的分桶结果先投影为 CTE 列，再按列分组，避免 PostgreSQL 把 SELECT 与 GROUP BY 中两个不同参数位置视为不同表达式。

| 输入 | 15 分钟步长、上述 origin 的结果 |
| --- | --- |
| `2024-01-01T12:34:56.123456` | `2024-01-01T12:32:30` |
| `2024-01-01T12:32:30` | 原值，精确边界不移动 |
| `1969-12-31T23:59:59.999999` | `1969-12-31T23:47:30` |
| SQL NULL | SQL NULL |

边界如下：

- `Duration` 必须为正且精确到微秒；拒绝零、负值、亚微秒值和转换为有符号长整数微秒时溢出的值。乘法和加法分别检查溢出。
- origin 必须非 NULL、精确到微秒；拒绝 pgJDBC 用作无穷大/无穷小哨兵的 `LocalDateTime.MIN/MAX`。其他日期范围继续由 PostgreSQL 类型边界校验。
- 此入口只有 `LocalDateTime`，不接收 `Instant`、`LocalDate`、`Period` 或带月份的 interval。无时区字段不会被自动当成 UTC 时间点。
- 月、年、自然日期桶继续使用 `ProDsl.truncateDate/truncateDateTime`；本入口的固定步长不能替代带时区自然日或自然月。对实际时间点和时区规则的支持须先完成对应 JDBC 类型契约。
- 查询行的 SQL NULL 保持 NULL。不要把缺失时间静默归入 origin 桶。

行为依据为 [PostgreSQL 17 date_bin](https://www.postgresql.org/docs/17/functions-datetime.html#FUNCTIONS-DATETIME-BIN)。本地验收采用 PostgreSQL 17.11、pgJDBC 42.7.14、JBR 17，`prepareThreshold=0/-1` 两模式均验证微秒步长、origin 之前的向下归桶、NULL、clone、CTE 分组与分页；纽约 JVM 时区及东京数据库会话下，DST 缺口和重叠的无时区字段也保持正确。

## 三库补齐月份后计算环比

先建立一行一个月份的日历来源。生产系统可以使用已有日历表；测试使用参数化的四行日期 `UNION ALL`，HANA 分支追加 `FROM DUMMY`。这不要求引入新的日历生成 DSL。

组合顺序为：

1. 事实按现有 `truncateDate(day, MONTH)` 分组，分别计算金额 SUM 与事实记录数 COUNT。
2. 从日历 LEFT JOIN 月度事实，保留没有事实的月份。右表业务过滤应在事实分组前或 ON 中完成。
3. 显示需要时单独将 COUNT 或金额转为零；保留原始 SUM，以区分没有金额与真实零值。
4. 在补齐后的月份上计算 LAG，再用 CTE 固定窗口边界，最后筛选展示范围、排序和分页。

已有窗口 API 可直接用于第 4 步：

```java
var report = filled.select(row -> Select.DRAFT.of(
        row.value1(), // month
        row.value2(), // original monthly amount
        row.value2().asBigDecimal()
                .offset(over -> over.orderBy(row.value1())).prev(1)))
        .toCteAs("calendar_report");
```

`filled` 来自日历 LEFT JOIN 月度聚合。LAG 不受 ROWS frame 影响，继续使用上游 offset API；不要给它套另一套 frame 规则。CTE 也让后续 SMART 展示筛选留在窗口之后。

三库共用用例的业务结果：

| 月份 | 事实 | 原始 SUM | COUNT | 显示金额 `COALESCE(SUM,0)` | 上月原始 SUM |
| --- | --- | --- | --- | --- | --- |
| 一月 | 10、20 | 30 | 2 | 30 | NULL |
| 二月 | 没有记录 | NULL | 0 | 0 | 30 |
| 三月 | 5 | 5 | 1 | 5 | NULL |
| 四月 | 一条 NULL 金额记录 | NULL | 1 | 0 | 5 |

三月的上月金额必须是 NULL；直接在稀疏事实上 LAG 会错误地读到一月的 30。完全没有事实时，四个日历月仍保留，COUNT 为零、原始 SUM 为 NULL。报表总行数为四，分页统计的是月份，不是原始销售记录数。

完整可运行组合见 `integration-tests/.../CalendarReportContract.java`，由 MySQL、PostgreSQL 与 HANA 的 ReportingIT 执行。该示例保留查询类型、原绑定和原事务路径，不引入报表执行器，也不把 `LAG(12)` 在缺月数据上称作同比。
