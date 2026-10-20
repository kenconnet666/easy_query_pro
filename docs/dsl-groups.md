# 分组汇总、ROLLUP 与汇总行标识

公共入口组合 EasyQuery 既有 `groupBy`、分组代理和 `EntityQueryable`。先声明键，再选择 ROLLUP 或数据库原生 GROUPING SETS，并在同一次调用中明确输出列。查询继续使用原有 JOIN、CTE、where、orderBy、count 和分页；没有平行查询执行器或最终 SQL 字符串改写。

## 共同 ROLLUP

```java
var grouped = query.queryable(Sale.class)
        .groupBy(s -> GroupKeys.of(s.region(), s.day()));

var report = ProGroups.rollup(grouped, g -> Select.DRAFT.of(
        g.key1(), g.key2(),
        g.groupTable().amount().sumBigDecimal(),
        ProGroups.mask(g, g.key1(), g.key2())));

var page = report.cloneQueryable()
        .orderBy(r -> {
            r.value4().asc();
            r.value1().asc();
            r.value2().asc();
        })
        .toPageResult(1, 20);
```

顺序 `region, day` 生成地区/日期明细、地区小计和全部总计。输入分组查询先克隆，原来的普通分组分支保留。上游查询对象仍有可变构建状态，各个终端消费或后续投影分支应先 `cloneQueryable()`，见[投影说明](dsl-projections.md)。

共同 ROLLUP 的空输入返回零行。MySQL/HANA 原生符合此规则，PG 原生保留空总计，增强包在该分组层追加 `HAVING COUNT(*) > 0`。已有 HAVING 作为完整括号条件保留，因此 OR 不会绕过空输入约束。统计值全部为 NULL 的非空输入仍有有效行数，不会被误删。

## 区分真实 NULL 和汇总 NULL

`ProGroups.mask(g, g.key1(), g.key2())` 按参数顺序返回 Integer 位掩码：

| 行类型 | 地区键 | 日期键 | mask |
| --- | --- | --- | --- |
| 明细 | 原值，包括真实 NULL | 原值，包括真实 NULL | 0 |
| 地区小计 | 原值，包括真实 NULL | 汇总占位 NULL | 1 |
| 总计 | 汇总占位 NULL | 汇总占位 NULL | 3 |

最右参数对应最低位，省略的键为 1。不能用 `key.isNull()` 替代 mask。HANA 使用 GROUPING_ID，PG/MySQL 使用 GROUPING；公共数量限制为 1..31，避免原生位宽不同。参数只能直接来自当前分组代理的 `keyN()`；同名其他表列、重新构造的等价表达式和重复键会被拒绝。

## PostgreSQL / HANA 分组集合

```java
var grouped = query.queryable(Sale.class)
        .groupBy(s -> GroupKeys.of(s.region(), s.day()));

var report = PgGroups.groupingSets(
        grouped,
        g -> List.of(
                GroupingSet.of(g.key1()),
                GroupingSet.of(g.key2()),
                GroupingSet.total()),
        g -> Select.DRAFT.of(
                g.key1(), g.key2(), g.count(),
                ProGroups.mask(g, g.key1(), g.key2())));
```

HANA 使用相同签名的 `HanaGroups.groupingSets`；MySQL 没有这个入口。集合成员是已声明键的子集，`total()` 表示空成员 `()`。重复键、重复成员、空成员列表和错方言调用提前报错；`(a,b)` 与 `(b,a)` 也视为重复成员。

原生集合保留方言空输入语义：包含总计成员时，PG 输出空总计，HANA 当前基线没有该行。若声明了一个键，却没有让它出现在任何成员中，随后仍投影或对它求 mask，数据库可能拒绝该 SQL；框架不会自动补 NULL 或把它偷偷加入集合。仅查询实际成员中的键是合法子集用法。

## 结果映射与查询边界

新 `Select.DRAFT.of(...)` 的 primitive 槽会改成可空结果载体，所以真实 `0` 和小计 NULL 可区分。原实体列 metadata、converter、encryption 和源代理属性类型不变；CTE、clone、重排列和单列读取沿用结果的可空契约。普通 DTO 保留自身字段/handler 契约，需保留 NULL 时使用 Integer、Boolean 等包装类型。显式用户 reader 继续优先，框架不猜测其内部 NULL 行为。

返回已绑定的来源 Draft，或直接返回分组代理自身，不能满足显式投影要求，会提前拒绝。应创建新的 Draft 或显式 DTO 列映射。

筛选位置决定业务语义：汇总前 where 改变参与统计的输入，汇总后 where 只决定展示哪些明细/小计。Pro 的 SMART provider 对实际扩展分组节点保留整个关系边界，避免地区 EQ/IN 被下推后重新生成本应排除的总计。count/page 统计报告输出行数，和“各分组 COUNT 的和”是不同指标。

带 JDBC 参数的计算键建议先 select 为派生列，再对该列 groupBy。尤其 PG 不一定把不同参数位置的两个计算表达式视为相同分组键；框架不手工复用参数编号。JOIN 的键继续通过原 `MergeTuple` 访问，多表来源不复制 GroupingN 类型族。

这些聚合依赖数据库看到完整输入，目前拒绝普通跨分片合并。CUBE、混合嵌套 grouping set 语法和动态 PIVOT 不在这个入口的范围内。

已用三库 JDBC 验证真实 NULL 与小计、空输入、原有 OR HAVING、SMART 外层筛选、参数键预投影、独立 JOIN 来源、克隆、CTE、分页/count，以及 primitive Draft 和两类 DTO。分组用例只使用常量关系，不创建数据库对象。共享读取器/语义 visitor 的回归同时覆盖已有窗口和统计报表；对应提交的完整 CI 结果单独记录于执行日志。

## 设计依据

[MySQL 的 ROLLUP/GROUPING 规则](https://dev.mysql.com/doc/refman/8.4/en/group-by-modifiers.html)、[PG 17 分组集合](https://www.postgresql.org/docs/17/queries-table-expressions.html#QUERIES-GROUPING-SETS) 和 [HANA GROUPING_ID](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/20e21cfd75191014a5cbbea031962073.html) 用于确认语法与位掩码。支持结论还需结合本项目真实 JDBC 及查询组合验证，官方语法支持不等于所有上游组合已经验收。
