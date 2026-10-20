# 窗口规格与报表查询

`ProDsl.window()` 创建不可变、绑定当前查询上下文的规格。共同能力包含 partition、升降序、空 partition 的全局窗口及 ROWS frame；SUM/AVG/COUNT/MIN/MAX 仍使用 EasyQuery 已有聚合表达式。

```java
var report = query.queryable(ReportSale.class).select(p -> {
    var pro = ProDsl.of(p);
    var ordered = pro.window().partitionBy(p.region()).orderBy(p.day(), p.id());
    var running = ordered.rowsBetween(RowBoundary.unboundedPreceding(), RowBoundary.currentRow());
    var previousTwo = ordered.rowsBetween(RowBoundary.preceding(2), RowBoundary.currentRow());
    return Select.DRAFT.of(p.id(), pro.over(p.amount().sumBigDecimal(), running),
            pro.over(p.amount().avg(), previousTwo), pro.over(p.amount().sumBigDecimal(), pro.window()));
});
```

`orderBy(columns...)` 追加升序项，`orderBy(column, false)` 追加降序项；相同排序值应按业务需要增加稳定次序。省略 frame 使用数据库默认规则，通常带 ORDER BY 的默认窗口会包含当前 peer group；它不等于显式 ROWS。NULL 排序继续采用目标数据库默认规则。

## 值函数与类型

`overValue(column, spec)` 复用上游 NextSelector 的 `firstValue()`、`lastValue()`、`nthValue(n)`。NTH 从 1 开始；这些入口要求明确排序。`prev/next` 对应 LAG/LEAD，它们不使用 frame，因此继续使用上游 offset API。

空 frame 的 SUM/MIN/MAX 与值函数保留 SQL NULL；COUNT 返回 0。来自 primitive 实体字段的值窗口也以包装类型读取 NULL。自动 source reader 对 primitive 使用可空读取，业务 converter 和显式 valueConvert 仍保持各自契约；DTO 的目标属性和转换由 DTO 自己决定。

`over` 接收裸聚合。先 `over(sum, spec)` 再 CAST/NULLIF，不能把 `CAST(SUM(...))` 当作可直接接 OVER 的函数。同层窗口嵌套也必须先经派生表/CTE。值窗口及窗口键只接受已知逐行表达式；无法证明其语义的上游复合表达式先投影成派生列，避免 UNKNOWN 隐藏聚合后错误承诺保留原行数。

## 复杂查询的边界

分组→派生表→窗口→外层筛选继续使用原 EntityQueryable。启用 SMART_PREDICATE 时，Pro 窗口层外的筛选不会被推入分区输入；即使筛选普通日期或地区，也可能改变其他全局窗口，因此整个窗口层都保留筛选边界。普通非窗口查询继续委托上游优化器。

直接 `distinct().count()` 会被上游改成 COUNT(DISTINCT window-expression)，这既不是合法的同层窗口用法，也会遗漏 NULL 结果。使用明确边界：

```java
var uniqueWindows = report.cloneQueryable().distinct().toCteAs("unique_windows");
long total = uniqueWindows.cloneQueryable().count();
var page = uniqueWindows.cloneQueryable().toPageResult(1, 20);
```

顺序必须是 DISTINCT 在 CTE 内。Pro 对直接优化路径提前报错，并检查透传别名与 clone；独立标量子查询及原生表达式中的子查询拥有自己的计算层，不会被误认为外层窗口。

该保护依据 Pro 表达式携带的语义 metadata。EasyQuery 3.2.17 的部分已有 offset/排名入口只提供 SQL 模板，缺少可访问的窗口标记；本包不扫描最终 SQL 来猜测。混用这些未标记入口时，用显式 CTE 固定窗口计算层，再做外层筛选/count/page。现有基础窗口函数保持原 API；完整 metadata 协作仍需上游公开 SPI。

窗口需要数据库完整分区，不能套用普通逐行或 SUM 的分片合并算法。Pro 窗口及其 CAST/NULLIF 包装在未支持的跨分片执行中明确拒绝。

分片检查也读取最终查询形状：克隆后在外层加入分片，不能绕过内层窗口的保护。共同 builder 沿实际投影、子查询、CTE 和 UNION 检查 Pro 语义节点，保留实际 JOIN 的 ON；未使用的导航关系不额外纳入检查。检查不把状态永久写入 context，因此 count 已删除的展示窗口不会继续阻止普通分片计数。应用扩展查询 builder 时应继承 `ProExpressionBuilderFactory` 并保留这些创建钩子；无需为普通窗口额外替换各库的 SQL ExpressionFactory。

## 可扩展规格

`WindowSpec<S>` 的 partition/order/frame 方法返回具体 S；应用实现 protected `recreate(State)`，通过 `pro.window(CustomWindow::new)` 挂载。State 会复制列表，派生规格不会修改旧规格。自定义 frame 通过 `WindowFrame` 提供受控校验及 SQLFunction，与原参数绑定机制组合，不接收任意未校验 SQL 字符串。

`over(aggregate, spec, ExpressionFactory)` 可以保留自定义结果表达式。扩展作者可用 `ExpressionScope.aggregate` 声明新的裸服务器聚合，必须保证其语法和空值契约；标量包装不会继承“裸聚合”标记。窗口与普通聚合的行数、分片行为分别分类。

## 方言边界与已知数据库行为

共同 ROWS 要求 ORDER BY，支持 unbounded/current/非负 N preceding/following，并要求结束位置不早于开始位置。0 归一为 CURRENT ROW。这个限制是 HANA、MySQL、PG 的共同范围：MySQL/PG 的原生同类倒序边界可以表示空 frame，HANA 2.00.088 会拒绝，不能称其为三库一致能力。

MySQL/PG 的数值和时间 RANGE 使用各自的原生规格；不复用 ROWS 的零归一化与位置比较。HANA 当前基线不支持目标 RANGE，不用 ROWS 代替最近 N 天。

## MySQL 与 PostgreSQL 原生 RANGE

两库均通过 `nativeWindow()` 增加 `rangeNumericBetween` 和 `rangeIntervalBetween`，共同 partition/order/ROWS 继续复用。数值窗口示例：

```java
var mysql = MySqlDsl.of(p);
var frame = mysql.nativeWindow().orderBy(p.amount())
        .rangeNumericBetween(RangeBoundary.preceding(new BigDecimal("0.10")), RangeBoundary.currentRow());
var total = mysql.over(p.amount().sumBigDecimal(), frame);
```

整数偏移绑定为 Long，精确小数保留 BigDecimal。PG 整型键要求整数偏移，NUMERIC 键可以使用小数偏移；MySQL 可对整型键使用小数距离。当前有限偏移的键限定为已知整型/精确数值，以及 LocalDate/LocalDateTime；Object/未知业务映射先作明确 nativeCast。浮点键和浮点偏移暂未开放，包括有限浮点数与 NaN/Infinity。

时间窗口分别使用以下类型，保留日期与日历含义：

```java
var mysql = MySqlDsl.of(p);
var mysqlDays = mysql.nativeWindow().orderBy(p.day()).rangeIntervalBetween(
        RangeBoundary.preceding(new MySqlRangeInterval(2, ChronoUnit.DAYS)), RangeBoundary.currentRow());
```

在 PostgreSQL 查询中使用对应入口：

```java
var pg = PgSqlDsl.of(p);
var pgMonth = pg.nativeWindow().orderBy(p.day()).rangeIntervalBetween(
        RangeBoundary.preceding(PgInterval.of(1, 0, 0)), RangeBoundary.currentRow());
```

MySQL 使用 INTERVAL 参数加受控单位，与 TIME 存储的 Duration 无关；固定 Duration 可以精确转换成微秒，不能舍弃纳秒尾部。PG 使用既有 PgInterval handler，需要按 PG 扩展的常规步骤调用 `PgSqlExtensions.configureTypeHandlers`。只接收非负 Finite 分量，Infinity 在泛型层排除，月份不换算为三十天。

有有限 offset 时要求一个实际排序项；零 PRECEDING/FOLLOWING 保留原类别，同类倒序边界可合法返回空窗口。相同排序值作为 peer group 一起进入 RANGE，降序的前后方向交给数据库解释。显式 NULL 排序、时区时间、GROUPS/EXCLUDE 尚未开放；当前遵循各库默认 NULL 排序。

`MySqlWindowSpec<S>`、`PgWindowSpec<S>` 都可以继承，应用实现 recreate 保留 S。共同挂载方法仅要求返回 WindowSpec 子类，不重复限制其 SELF，因此既接受 `pro.window(PgWindowSpec::create)`，也接受 `pro.window(ReportPgWindow::new)`。

实库验收覆盖 MySQL 客户端/服务端 prepare（禁止静默降级）、PG 普通/强制 prepare；重复值、NULL 键、精确小数、零和空 frame、升降序、日期间隙、闰月边界、微秒距离、CTE/count/page 及参数克隆均有固定期望值。

MySQL 8.4.11 对 `(SELECT 7 AS n)` 常量单行来源的 `ROWS BETWEEN 1 FOLLOWING AND 1 FOLLOWING` 存在预执行优化差异：原生 COUNT 返回 1、FIRST/MIN 等返回 7，执行计划仅为 `Rows fetched before execution`。两行 UNION 的末行以及单行 JSON_TABLE 来源都执行正常窗口、得到空 frame。该结果已通过独立原生 SELECT/EXPLAIN 核实；本包不加入 SQL 补偿。空 frame 回归采用非恒定输入并检查最后一行。

三库的分组累计、移动均值、全局总额、值窗口、NULL、外层 EQ/IN/分页及 DISTINCT→CTE 契约由 `WindowReportContract` 共享验证。测试和 CI 的实际证据按提交记录在 execution.md，不把文档示例当作验收结果。
