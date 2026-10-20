# 报表与聚合查询

报表沿普通 Query 执行，复用字段类型、参数、事务与结果解码。先聚合事实关系，再进行维度 JOIN 或下一层汇总，可以明确控制重复行与分组范围。

## 命名分组键

```ts
import {
  aggregate,
  column,
  coalesce,
  table,
  types,
  valueOf,
  frame,
  windowFunctions,
  windowSpec,
} from "@easy-query-pro/orm";

const sales = table("sale", {
  id: column(types.integer()).primaryKey(),
  team: column(types.text(100)),
  amount: column(types.decimal(19, 4)).nullable(),
  enabled: column(types.boolean()),
});
const report = db
  .from({ sale: sales })
  .where(({ sale }) => sale.enabled)
  .groupBy(({ sale }) => ({ team: sale.team }))
  .having(({ sale }) => aggregate.sum(sale.amount).gt("10.0000"))
  .select(({ sale }, extensions, keys) => ({
    team: keys.team,
    total: aggregate.sum(sale.amount),
    facts: aggregate.count(),
    nonNullAmounts: aggregate.count(sale.amount),
  }));
```

第四个 Query 泛型保存命名分组键；where/join/order/limit 等链式调用保持它，select、having、orderBy 的第三个参数给出这些键。groupBy 再次调用会替换本层分组定义。键对象先取不可变快照，getter 只执行一次。

SELECT、HAVING 和 ORDER BY 中，聚合之外的列必须由分组键覆盖。按 name 分组可以选择 upper(name)；按 upper(name) 分组时，应复用 `keys.name` 对应的完整计算键。没有按主键推断其他列依赖的隐式规则。带参数的计算分组键先投影到派生来源，再对其结果列分组，避免 PostgreSQL 把不同位置的参数视为不同表达式。

## 空输入、NULL 与条件聚合

| 表达式                             | 结果类型        | 空输入/全 NULL             |
| ---------------------------------- | --------------- | -------------------------- |
| aggregate.count()                  | bigint          | 0                          |
| aggregate.count(column)            | bigint          | 只计非 NULL 值，空输入为 0 |
| aggregate.count(column).distinct() | bigint          | 只计不同非 NULL 值         |
| aggregate.min/max(column)          | 原值类型与 null | null，保留业务 codec       |
| aggregate.sum(column)              | string 与 null  | null，不先填 0             |

没有分组键的全局聚合即使输入为空也有一行，因此该报告的 Query.count() 为 1；带分组键的空输入没有分组，Query.count() 为 0。

`.filter(predicate)` 把未满足条件的输入变为 NULL。FALSE 与 UNKNOWN 都不贡献值；COUNT(*) 的条件形式计 CASE 的 1，其他聚合计来源值。条件中不能包含同层聚合或窗口。多个 filter 按 AND 组合。

`caseWhen(condition, whenTrue, whenFalse)` 在 FALSE/UNKNOWN 时使用 else；`coalesce(left, right)` 只替换 SQL NULL；`nullIf(left, right)` 在相等时返回 NULL。值分支要求 SQL storage 与完整 codec 链相同，可空性按 SQL 规则推导。HANA 的 WHEN 条件使用条件渲染上下文，避免把它错误当成普通 BOOLEAN 字符串片段。

展示用零值在汇总后单独处理，例如 `coalesce(total, valueOf("0", types.decimal(38, 4)))`。保留原 sum 和 fact count，才能区分没有事实与事实值全部为 NULL。

## SUM 的数值边界

普通 INTEGER/BIGINT 输入先转换为 DECIMAL(38,0)，DECIMAL 输入先转换为 DECIMAL(38,原 scale)，再 SUM。HANA 的整数累加不会先在原 INTEGER/BIGINT 范围中溢出。读取结果仍检查共同 38 位范围，不把结果外层强行 CAST 回窄类型，避免 MySQL 的夹值行为。

数值来源标记随着投影、派生表、CTE 和 UNION 传播。已有 SUM 累计值在下一层 SUM 中直接使用数据库的宽中间值，因此可以做“日汇总 → 团队汇总”。任意算术或原生函数的范围无法从左操作数的 storage 推导，直接及经派生来源隐藏后都会拒绝自动提升。

这类表达式需要显式 `nativeCast(expression, type)` 选择转换边界。nativeCast 采用数据库自己的舍入、截断和溢出规则，不保证原数学表达式无损；它在 API 上保持可见。SUM 的结果范围检查也不代表每个数据库中间计算步骤都按同一种 38 位算法执行。

AVG、RANGE、分组集和更完整的统计函数继续按执行计划推进；当前没有用不受验证的 number 泛型冒充相同的精度与舍入结果。

## 窗口、排名与前后行

```ts
const source = db.from({ sale: sales });
const order = windowSpec().partitionBy(source.scope.sale.team).orderBy(source.scope.sale.id.asc());
const computed = source.select(({ sale }) => ({
  id: sale.id,
  team: sale.team,
  running: aggregate
    .sum(sale.amount)
    .over(order.rowsBetween(frame.unboundedPreceding, frame.currentRow)),
  previous: windowFunctions.lag(sale.amount).over(order),
  sequence: windowFunctions.rowNumber().over(order),
}));
const shown = db
  .from({ result: computed.asCte("calculated_sales") })
  .where(({ result }) => result.id.gt(100))
  .select(({ result }) => ({ id: result.id, running: result.running }));
```

WindowSpec 不可变，partitionBy/orderBy 追加，rowsBetween 设置新的 frame。来源字段必须来自当前 query.scope 或回调参数；不同 from 调用即使别名相同也不能混用。窗口内部排序决定计算顺序，最终展示顺序仍由普通 orderBy 指定。

| 函数                                   | ORDER BY                 | ROWS frame | 结果            |
| -------------------------------------- | ------------------------ | ---------- | --------------- |
| 裸 aggregate.count/sum/min/max 的 over | 有 frame 时必需          | 支持       | 沿聚合结果类型  |
| rowNumber                              | 可省略，省略后编号不稳定 | 拒绝       | bigint          |
| rank/denseRank                         | 必需                     | 拒绝       | bigint          |
| lag/lead                               | 必需                     | 拒绝       | 原值类型与 null |
| firstValue/lastValue/nthValue          | 必需                     | 支持       | 原值类型与 null |

COUNT 的空 frame 返回 0，SUM/MIN/MAX/FIRST/LAST/NTH 的空 frame 返回 NULL。LAG/LEAD 的第三个默认值只处理缺少目标行；目标行已有 SQL NULL 时仍返回 NULL，不能用 COALESCE 替代它。默认值可以是业务值或匹配 storage/codec 的表达式，继续经过参数绑定。

没有声明 frame 时保留数据库默认 peer 范围，不自动补 ROWS，也不自动附加主键改变 RANK 的相等组。LAST_VALUE 的默认窗口通常只到当前行及 peers，需要整个分区的最后值时显式使用 unboundedPreceding 到 unboundedFollowing。

ROWS 偏移统一限定在本项目范围 0–2147483647；NTH 的位置从 1 开始。零偏移正规化为 CURRENT ROW，起点不能是 UNBOUNDED FOLLOWING，终点不能是 UNBOUNDED PRECEDING，共同入口不接受反向范围。原生实测中 PG/MySQL 将 1 PRECEDING 到 2 PRECEDING 视为空 frame，而 HANA 2.00.088 拒绝。因此这里是已验证的共同范围，不是把 PostgreSQL 的合法语法统称为非法。[PG frame 规则](https://www.postgresql.org/docs/17/sql-expressions.html#SYNTAX-WINDOW-FUNCTIONS)

偏移校验后生成受控整数字面量，避免 MySQL LAG/LEAD 位置不接受 CAST(?) 的语法差异；业务默认值仍用参数。排名、LAG/LEAD 拒绝会被某些数据库忽略的 frame，DISTINCT OVER 也不作为共同能力开放。[MySQL frame 行为](https://dev.mysql.com/doc/refman/8.4/en/window-functions-frames.html)

普通和窗口排序都可写 `.asc("first")`、`.desc("last")`。PG/HANA 使用原生 NULLS，MySQL 使用 NULL 标记排序键加原表达式；每次 SQL 出现位置独立绑定。未来 RANGE 的单排序键限制会继续按实际物理键计算。

已有 GROUP BY/HAVING/普通聚合的查询先通过 as()/CTE 形成新的行来源，再施加窗口。同层窗口不允许嵌套，WHERE/HAVING 中也不能隐藏窗口。展示过滤放在窗口结果的外层，才能保留已经计算的累计值、全局总额和上一行；上例明确展示了这个层级。Query.count() 保留窗口和 DISTINCT 的实际输出语义。
