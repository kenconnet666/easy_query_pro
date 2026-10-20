# EXISTS、标量与相关子查询

`Query.exists()` 和 `Query.scalar()` 把当前查询变为表达式，仍由父查询统一编译和执行，不访问数据库、不提前编译 SQL 或拼接参数数组。两者都要求显式 select；scalar 还要求恰好一个投影字段。

```ts
const report = db
  .from({ user: users })
  .orderBy(({ user }) => user.id.asc())
  .select(({ user }) => {
    const ordersForUser = db.from({ order: orders }).where(({ order }) => order.userId.eq(user.id));
    return {
      id: user.id,
      hasOrders: ordersForUser.select(({ order }) => ({ id: order.id })).exists(),
      orderCount: ordersForUser.select(() => ({ count: aggregate.count() })).scalar(),
      total: ordersForUser.select(({ order }) => ({ total: aggregate.sum(order.amount) })).scalar(),
    };
  });
```

EXISTS 结果为非空 boolean，可以传给 where、and、not 或放入投影。上例 COUNT 的实际 SQL 结果是 0/1/2 等，但通用 scalar 静态类型仍是 bigint|null，因为它也允许添加 HAVING 等使查询无行。SUM 保留空输入/全 NULL 语义。

scalar 沿唯一投影的 DataType、codec、原生 storage 和数值来源创建可空表达式。零行返回 SQL NULL；一行本来就为 NULL 也返回 NULL；多行由数据库报错。框架不偷偷加 LIMIT 1。业务 Money、JSON null sentinel、PG 数组等仍由原 codec 读取，可在 scalar 之后重新 mount 方言或业务 API。

## 捕获、别名和查询层级

内层 callback 通过闭包引用祖先表达式，类型与关系身份都保留。内外层同叫 `user` 不会让条件变成错误的自比较：编译器为冲突的 SQL alias 分配别名，公共 scope 键不变。两层关联已通过三库验证。

每个子查询从新的 `db.from(...)` 开始。把同一个 base 自己嵌入自己会重复 relation identity，当前明确拒绝；clone/select 分支用于复用查询内容，不能用来声称它是另一次独立表绑定。同 client 的无关查询列也不可见，跨 client 子查询同样拒绝。JOIN ON 只看到祖先和当前 JOIN 为止的来源，不能引用后面的 JOIN。

同层表达式 visitor 不进入内层聚合，因此内层 COUNT 不会把父查询误判成分组查询；自由列分析则仍检查内层捕获的祖先引用。

- 外层 WHERE 的关联过滤可以发生在 GROUP BY 之前。
- 已分组查询的 SELECT/HAVING/ORDER 中，当前要求先把分组结果放入派生关系或 CTE，再做相关子查询。这样避免未分组外层列被子查询包装后逃过检查。
- 聚合或窗口的输入不能直接捕获祖先列。PG 对全部参数来自外层的 aggregate 有特殊归属规则，当前不猜测这类跨层归属。
- 普通 FROM 派生关系、CTE 与 UNION 分支不能捕获外层行，不隐式启用 LATERAL。CTE 仍在同一个顶层 WITH 中按依赖顺序声明。
- 写入和普通 command 尚未开放子查询作用域，提前明确拒绝；本批支持的是 SELECT 表达式查询。

CTE 即使只在子查询里使用，也会收集一次。相同 CTE 的多次引用共享声明，绑定在 WITH 的实际位置产生；普通 scalar 重复出现在两个位置则分别产生绑定。依赖同名、循环、物理表遮蔽和 owner 校验继续生效。

## 保留实际行数语义

EXISTS 保留原投影、聚合、DISTINCT、HAVING、排序和分页。例如空输入上的 `SELECT COUNT(*)` 仍有一行，存在性应为 true；DISTINCT 后的 OFFSET 计算的是去重后的行，不能把投影替换成 SELECT 1 再分页。

MySQL 8.4 的原生 EXISTS 可能忽略 DISTINCT 投影。实库发现“去重后只有三行、OFFSET 3”仍返回 true，而其他两库为 false；因此编译器仅在 MySQL 的 DISTINCT＋非零 OFFSET 场景增加派生关系，先完成原查询的去重/分页，再判断该关系是否存在。关联与非关联版本均有固定结果验证，不依靠 SQL 文本解析。官方语义见 [MySQL EXISTS](https://dev.mysql.com/doc/refman/8.4/en/exists-and-not-exists-subqueries.html)。

HANA 的投影 EXISTS 使用一次 `CASE WHEN EXISTS(...) THEN TRUE ELSE FALSE END`，不会复制整份子查询来模拟三值逻辑，因为 EXISTS 自身不返回 UNKNOWN。HANA 相关子查询含排序或分页时目前提前拒绝，不删除子句改变结果；PG/MySQL 的相关排序＋LIMIT 1 已实测。

## 扩展 renderer 的依赖声明

扩展函数可以包装、重排或重复子查询参数，但必须把它们传入 `call(..., arguments)`，不能在 renderer 闭包内另造一个查询：

```ts
const wrapped = call(
  {
    name: "query-default-zero",
    phase: "row",
    render: { postgres: (args) => sqlFragment`COALESCE(${args[0]!}, 0)` },
  },
  countExpression.type,
  [countExpression],
);
```

编译器在 renderer 展开后核对这条依赖边。否则 CTE 排序、名称遮蔽及分组检查看到的图会与实际 SQL 不一致。该限制也覆盖已在其他位置出现的子查询：另一处声明不是当前 call 隐藏依赖的许可。

设计主参考 EasyQuery 3.2.17 的结构化子查询与共享 ToSQLContext；TS 使用现有不可变 QueryNode，无需引入另一套可变 clone/context。零行/多行标量和外层聚合归属规则见 [PG 17 值表达式](https://www.postgresql.org/docs/17/sql-expressions.html#SQL-SYNTAX-SCALAR-SUBQUERIES)。具体证据与限制见 [执行记录](evidence.md)。
