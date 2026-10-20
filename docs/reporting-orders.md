# 订单指标、加权均价与部门 Top N

这些报表直接组合 EasyQuery 3.2.17 的 GroupKeys、条件聚合、排名、CTE 与分页，以及 Pro 的精确 CAST、NULLIF 和窗口规格。没有新增 sumIf、countIf、percentage 或报表执行器。三库共用 `OrderReportContract` 的固定结果验收。

## 条件指标和 NULL

每个指标创建自己的聚合对象，再调用 `filter`。上游 filter 会改变该对象；不要把同一个 SUM 先用于总额，再修改为已付款金额，最后期待先前引用仍代表总额。

还必须区分上游条件聚合的实际语义：

- `SUM.filter` 对不匹配的行生成 ELSE 0。因此一个非空组没有付款时，付款金额为 0。
- 如果一个组中匹配的金额全是 NULL，但还有不匹配行，那些 ELSE 0 也可能使结果变成 0。
- 如果匹配的金额全部为 NULL，且没有其他 ELSE 0 行，SUM 结果仍是 NULL。
- 整个输入为空时，SUM 返回 NULL，COUNT 返回 0。
- `count(true).filter` 可直接复用客户去重，并忽略 NULL 客户。无需新增客户统计 API。

所以金额合计旁边应保留付款行数和非 NULL 金额行数，不能只凭结果 0 推断“所有金额已知且为零”。

当前 13 行订单 fixture 的主要结果如下。R 只有退款；U 有一条金额未知的付款和一条退款；NULL 部门只有金额未知的付款。

| 部门 | 付款数 | 退款数 | 付款客户去重 | 付款金额 | 付款数量 | 退款金额 | 非 NULL 付款金额数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 3 | 1 | 2 | 50 | 5 | -5 | 3 |
| B | 2 | 0 | 2 | 0 | 0 | 0 | 1 |
| D | 2 | 0 | 2 | 40 | 4 | 0 | 2 |
| E | 1 | 0 | 1 | 40 | 4 | 0 | 1 |
| R | 0 | 1 | 0 | 0 | 0 | -7 | 0 |
| U | 1 | 1 | 1 | 0 | 0 | -2 | 0 |
| NULL | 1 | 0 | 0 | NULL | NULL | 0 | 0 |

## 在求和前确定 SQL 数值域

Java 的 `sumBigDecimal()` 返回类型不是 SQL CAST，`asBigDecimal()` 也不能替代输入类型提升。数量与金额应按业务精度先转换，再聚合：

```java
var grouped = orders.groupBy(order -> GroupKeys.of(order.region()))
    .select(group -> {
        var order = group.groupTable();
        var pro = ProDsl.of(order);
        var decimal = CommonTypes.decimal(19, 4);
        return Select.DRAFT.of(group.key1(),
            pro.nativeCast(order.amount(), decimal).sumBigDecimal()
                .filter(() -> order.status().eq("PAID")),
            pro.nativeCast(order.quantity(), decimal).sumBigDecimal()
                .filter(() -> order.status().eq("PAID")));
    });

var prices = grouped.select(row -> {
    var pro = ProDsl.of(row);
    var amount = row.value2().asBigDecimal();
    var quantity = row.value3().asBigDecimal();
    return Select.DRAFT.of(row.value1(),
        pro.nativeCast(amount.divide(pro.nullIfValue(quantity, BigDecimal.ZERO)),
                       CommonTypes.decimal(19, 6)));
});
```

这里 Draft 中两列已经由 DECIMAL 输入聚合产生，`asBigDecimal` 只是取回相应表达式族。分母为零或 NULL 的均价返回 NULL，不伪装成零价格。

示例中 A 的 3 个订单金额分别为 20、10、20，数量分别为 2、1、2，因此加权单价为 50 / 5 = 10，而不是按订单行数算 50 / 3。D 的两笔金额为 30、10，数量为 1、3；先算单笔单位价再简单平均也不等于正确的 40 / 4。B、R、U 和 NULL 部门没有可用的非零数量，均价为 NULL。另一个两行 fixture 的数量各为 1,500,000,000，显式提升后总数量精确读为 3,000,000,000，确认没有把 Java 返回类型误当作 SQL 累加域或沿原 Integer reader 读取总和。

部门份额在分组后以无分区 SUM 窗口取得付款总额 130，再除以 NULLIF(total, 0)。结果投影进 CTE 后才执行展示筛选和分页；筛选 A 不会把分母改成 A 自己的金额。当前输出显式 CAST 到 6 位小数，A 为 0.384615，D/E 各 0.307692，舍入后的份额之和可能存在末位差。

## Top N 与并列排名

先限定排名范围：只对已付款且金额已知的订单排序。排名继续使用上游三个入口：

```java
var ranked = orders.where(order -> {
        order.status().eq("PAID");
        order.amount().isNotNull();
    })
    .select(order -> Select.DRAFT.of(order.id(), order.region(), order.amount(),
        order.expression().rankOver().partitionBy(order.region())
            .orderByDescending(order.amount()),
        order.expression().denseRankOver().partitionBy(order.region())
            .orderByDescending(order.amount()),
        order.expression().rowNumberOver().partitionBy(order.region())
            .orderByDescending(order.amount()).orderBy(order.id())))
    .toCteAs("ranked_paid_orders");

var topPeers = ranked.where(row -> row.value4().eq(1L));
```

RANK 和 DENSE_RANK 不追加 id，否则并列记录会被拆成不同名次。只有 ROW_NUMBER 用 id 作为稳定次排序键。fixture 中 A 有金额 20、20、10，分别得到 RANK 1/1/3、DENSE_RANK 1/1/2；ROW_NUMBER 为 1/2/3。

按部门取第一名时，RANK 返回 5 行，ROW_NUMBER 返回 4 行。取前两名时，RANK 为 6 行，DENSE_RANK 为 7 行。窗口结果在外层 CTE 上筛选，启用 SMART 后仍验证 count/page 与稳定排序，不能把窗口表达式塞进同层 WHERE。

本节是已有分区排名的消费示例。上游 builder 不支持空 partition 的缺口另行处理，不用人为常量分区冒充新的全局排名入口。

13 个月的同比/环比组合见 [时间序列报表](reporting-time-series.md)。对应测试按 clone 后执行各终端操作，参数和 SQL 重复渲染保持稳定，真实执行证据见 [执行记录](execution.md)。
