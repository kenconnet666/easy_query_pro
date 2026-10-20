# 方差与标准差

共同 DSL 提供四个区分总体与样本的入口，返回现有 `NumberFilterTypeExpression<Double>`：

| 方法 | 数据库函数 | 有效样本数 |
| --- | --- | --- |
| `variancePopulation` | VAR_POP | 至少 1 个；单样本为 0 |
| `varianceSample` | VAR_SAMP | 至少 2 个；单样本为 NULL |
| `standardDeviationPopulation` | STDDEV_POP | 至少 1 个；单样本为 0 |
| `standardDeviationSample` | STDDEV_SAMP | 至少 2 个；单样本为 NULL |

NULL 输入不计入样本；空集、全 NULL 和筛选后没有样本都返回 NULL。接口接收数值列表达式，字符串或 JSON 文本先作明确数据库转换，不能靠泛型强转宣称它是数值。

```java
var metrics = query.queryable(ReportSale.class)
        .groupBy(p -> GroupKeys.of(p.region()))
        .select(g -> {
            var pro = ProDsl.of(g.groupTable());
            return Select.DRAFT.of(g.key1(),
                    pro.variancePopulation(g.groupTable().amount()),
                    pro.standardDeviationSample(g.groupTable().amount())
                            .filter(() -> g.groupTable().id().le(100)));
        });
```

条件样本沿用 EasyQuery 的 `filter`，内部 CASE 未匹配分支为 NULL。不能用 SUM 专用的 ELSE 0 形式：零也是样本，会改变方差和标准差。没有新增 varianceIf 等平行入口。分组、HAVING、DTO/Draft、CTE 和普通排序继续使用现有查询 API。

## 近似精度契约

这四项是统一的近似统计：输入在聚合前转为 DOUBLE，输出为可空 Double，不继承输入列的业务读取器。HANA/PG 复用已有 CAST，MySQL 显式生成 CAST AS DOUBLE，因为 3.2.17 的通用 Double CAST 会生成 DECIMAL(32,16)。该分支仅属于统计计算，不全局改变 MySQL 原 CAST 行为。

选择聚合前转换有实际依据：HANA 原生 DECIMAL 统计会受输入 scale 限制。例如 DECIMAL(19,4) 的 0.0001、0.0002 原生结果可能舍入为 0；聚合后再转 Double 已无法恢复。共同近似计算应分别得到总体方差 2.5e-9、样本方差 5e-9。

Double 仍有明确表示范围和精度。9007199254740992、9007199254740993 转 Double 后相同，因此方差为 0。这不是任意精确金额或大整数统计的替代品；精确原生数值统计另行设计，不静默改变业务列的存储类型。非有限值和超出 DOUBLE 范围的输入不宣称跨库等价。

## 窗口、复用与查询边界

统计表达式可以直接挂到同一套窗口规格，保留现有 `filter`：

```java
var running = query.queryable(ReportSale.class).select(p -> {
    var pro = ProDsl.of(p);
    var frame = pro.window().partitionBy(p.region()).orderBy(p.id())
            .rowsBetween(RowBoundary.unboundedPreceding(), RowBoundary.currentRow());
    return Select.DRAFT.of(p.id(),
            pro.over(pro.varianceSample(p.amount()).filter(() -> p.id().le(100)), frame));
});
```

已有统计或窗口的结果先投影成派生列，再作为新统计的样本。CAST/NULLIF 等外层包装放在 OVER 之后。未知上游包装没有足够 metadata 证明任意嵌套合法，数据库仍负责最终 SQL 约束；这不变成一个 SQL 解析器。

不带 GROUP BY 的单行聚合报表在需要 count/page 时，先 `toCteAs(name)` 固定结果层，避免上游 count 优化移除聚合投影。已分组报表的 count 统计报表行数，不是把各组样本数相加。独立分支在终端查询之前 clone，沿用 [投影复用说明](dsl-projections.md)。

没有定义跨分片方差/标准差合并算法，因此统计及其包装会拒绝这类执行；不能把它们标成 SUM/AVG 来复用错误的合并逻辑。

验证使用同一组销售数据，覆盖条件样本、NULL、空输入、单样本、总体/样本区别、微小小数、Double 大整数边界、分组 count/page、累计窗口和 JDBC 原生返回类型。具体版本与当前提交证据见 [执行记录](execution.md)。
