# 分级小计 ROLLUP

`rollupBy(keys)` 按键的声明顺序生成前缀小计。两个键 country/city 产生 country＋city 明细组、country 小计与总计；它与普通 groupBy 共用原查询、参数、聚合、codec 和事务路径。

```ts
const totals = db
  .from({ sale: sales })
  .rollupBy(({ sale }) => ({ country: sale.country, city: sale.city }))
  .orderBy((_, __, keys) => [
    grouping(keys.country).asc(),
    keys.country.asc(),
    grouping(keys.city).asc(),
    keys.city.asc("first"),
  ])
  .select(({ sale }, _, keys) => ({
    country: keys.country,
    city: keys.city,
    countryTotal: grouping(keys.country),
    cityTotal: grouping(keys.city),
    facts: aggregate.count(),
    amount: aggregate.sum(sale.amount),
  }));
```

`grouping(key)` 返回 0 或 1：0 是实际分组键，值本身可以是真实 NULL；1 才表示该层把键汇总掉了。例如城市 NULL 的明细组与国家小计都显示 city=null，但前者 cityTotal=0，后者为 1。不要通过 IS NULL 判断是不是小计。

## 小计 NULL 与类型

即使数据库列声明 NOT NULL，总计行也可以返回 NULL。因此 rollupBy 返回新的可空 scope 和命名 groupKeys，原表、原查询分支与业务 codec 不变。SQL NULL 在业务 map 之前处理，国家值对象等构造器不会收到总计的 null。

rollupBy 清掉此前的 select，类型变为尚未投影，要求之后重新 select。闭包可能重新引用之前保存的非空表达式，因此编译时也检查不安全的投影。例如旧 country 列或旧 `country.upper()` 不能继续声明为非空。使用本次 callback 的 scope/groupKeys，或用公共 coalesce 显式提供非空展示值。

```ts
const labeled = rolled.select((_, __, keys) => ({
  label: coalesce(keys.country, valueOf("TOTAL", countryType)),
}));
```

这只是展示标签，不替代 GROUPING 标记。一个可能为 NULL 的函数/CAST 加上旧非空键的 COALESCE，也不能隐藏总计时两个输入都为 NULL 的情况。

普通 `groupBy` 会把 SQL 模式明确复位；在已可空的 ROLLUP 分支继续普通分组，类型可以保守保持 nullable，但不会继续输出 ROLLUP。

## 共同范围与空输入

共同入口目前接收已投影的列作为键。需要 UPPER、日期桶或带参数计算时，先 select → as()/CTE，再 rollupBy 结果列。GROUPING 匹配本层列身份，不按 SQL 字符串猜测表达式相等；业务 `.map()` 不改变 SQL 列引用，因此可直接保留。

PG/HANA 渲染 `GROUP BY ROLLUP(...)`，MySQL 使用兼容的 `GROUP BY ... WITH ROLLUP`。这项语法选择不表示 MySQL 8.4 完全不支持前一种写法。当前没有开放 CUBE、任意 GROUPING SETS 或多键位掩码。

空输入沿本仓 Java 的既有共同合同：返回空结果。当前 PG 17.11 原生空 ROLLUP 会生成一条总计，MySQL 8.4.11/HANA 2.00.088 不会；编译器只为 PG 追加 `HAVING COUNT(*) > 0`，已有 HAVING 先整体加括号再 AND，避免 OR 分支绕过空集约束。物理空表和 WHERE 无匹配两种情况均已实测。这个约定不改变普通无分组聚合在空输入时仍有一行的行为。[PG grouping sets](https://www.postgresql.org/docs/17/queries-table-expressions.html#QUERIES-GROUPING-SETS)、[MySQL 空 ROLLUP 行为记录](https://bugs.mysql.com/bug.php?id=114638)

GROUPING 只能用于本层 ROLLUP 键，放在 SELECT、HAVING 或 ORDER BY；不能放在 WHERE、JOIN 条件、同层聚合或窗口输入。需要下一层统计时先生成 CTE，例如外层 SUM(groupingFlag) 统计小计行数。分组后做窗口也继续要求先派生。

## 扩展与验证

renderer 的列、子查询和 typed GROUPING 依赖必须通过原 arguments 声明。不能只传一个原始列，却在 renderer 闭包里临时造 GROUPING 节点，否则 WHERE/窗口阶段的语义检查会看不到它。正常传入 `grouping(key)` 作为参数再包装仍可用；编译时按真实 scope 核对键。

五行测试数据同时包含重复城市、真实 NULL 城市及未知金额，得到七行明细/小计/总计。三库验收了业务对象映射、SUM/COUNT、GROUPING 标记、NULL 排序、HAVING、CTE筛选、count/page、展示 coalesce 与派生计算键；总额为42、事实数为5。只读审查发现的旧非空 fallback 和隐藏 GROUPING 路径均有先失败后通过的负例。详见 [验证记录](evidence.md)。
