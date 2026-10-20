# 浮点测量与近似平均值

精确金额继续用 decimal 字符串，大整数继续用 bigint。`types.float64()` 明确选择近似的有限双精度 number，适合测量值和允许数值容差的统计结果；它不会因 JavaScript 载体也是 number 而与 integer 成为相同 SQL 类型。

```ts
const measurements = table("measurement", {
  id: column(types.integer()),
  sensor: column(types.text(100)),
  value: column(types.float64()).nullable(),
});
const averages = db
  .from({ m: measurements })
  .groupBy(({ m }) => ({ sensor: m.sensor }))
  .select(({ m }, _, keys) => ({
    sensor: keys.sensor,
    samples: aggregate.count(m.value),
    average: aggregate.avgApprox(m.value),
    minimum: aggregate.min(m.value),
    maximum: aggregate.max(m.value),
  }));
```

float64 参数只接收 primitive number。结果可来自驱动的 number 或严格十进制/科学计数法文本；NaN、Infinity、十六进制、空白、布尔值、bigint 和对象都不会被隐式转换。文本非零值若小到转换为 JS 0 会明确拒绝。负零统一为正零，以匹配三库共同业务语义。

## AVG 的计算域

`aggregate.avgApprox` 接受 integer、bigint、decimal、float64，返回 `AggregateExpression<number | null, "float64">`。精确输入先在数据库 CAST 为 DOUBLE PRECISION（PG）或 DOUBLE（MySQL/HANA），再 AVG；已经是 float64 的输入无需重复转换。MySQL 8.4 支持明确的 DOUBLE CAST，不使用会受 REAL_AS_FLOAT 影响的 REAL。[MySQL CAST](https://dev.mysql.com/doc/refman/8.4/en/cast-functions.html)

这是有意的近似入口，不是精确 AVG 的别名。HANA 的 DECIMAL AVG 返回域沿输入精度/scale，MySQL 的 DECIMAL AVG scale 受 div_precision_increment 影响，PG 的 numeric 又有自己的计算规则。只在 AVG 结果后加 CAST 不能恢复此前已经舍掉的精度。参见 [PG 聚合类型](https://www.postgresql.org/docs/17/functions-aggregate.html)、[HANA AVG](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/2c93334adf654054a948cc7f151887de.html) 和 [MySQL AVG 实现](https://github.com/mysql/mysql-server/blob/8.4/sql/item_sum.cc#L2124-L2137)。

`.filter()`、`.distinct()` 与 `.over()` 复用现有聚合/窗口合同。空输入、全 NULL、过滤后无样本和空 frame 返回 NULL。MIN/MAX 保留输入业务 codec，AVG 则返回标准 number，应用可显式挂载自己的结果 API。

有两个转换次序需要调用方决定：

- `avgApprox(value).distinct()` 在转换后的 DOUBLE 域去重。2^53 与 2^53+1 可能合并；需要按精确原值去重时，先把原值 select → distinct → as()/CTE，再在外层 avgApprox。
- AVG 的 CAST 作用于整个输入表达式，不会自动重写其内部运算。如果整数乘法本身会溢出，应先显式把运算数转为 float64，再乘，而不是期待外层 AVG 帮忙扩大每个中间值。

当前精确 SUM 不接受 float64，也不会把它自动变成 DECIMAL(38,s)。是否将近似值转换为业务指定精度，必须由调用方通过 nativeCast 明确选择。AVG 与下面四项统计的数值来源都标为 computed，经过派生关系、CTE 和 scalar 仍保留。统一精确 AVG 与近似 SUM 继续单独规划。

## 总体与样本的离散程度

四个入口复用 AVG 的 DOUBLE 输入和有限 float64 结果域，继续支持 `.filter()` 与 `.over()`：

| 入口                              | 原生函数    | 没有有效样本 | 一个有效样本 |
| --------------------------------- | ----------- | ------------ | ------------ |
| variancePopulationApprox          | VAR_POP     | NULL         | 0            |
| varianceSampleApprox              | VAR_SAMP    | NULL         | NULL         |
| standardDeviationPopulationApprox | STDDEV_POP  | NULL         | 0            |
| standardDeviationSampleApprox     | STDDEV_SAMP | NULL         | NULL         |

有效样本只计非 NULL 值，条件不满足的行继续贡献 NULL，不能改成 0。样本方差以 n-1 为分母；框架使用明确的 POP/SAMP 函数名，不映射到各数据库语义可能不同的 VAR、VARIANCE 或 STDDEV 别名。标准差使用数据库原生函数，不把方差公式或平方根计算搬到客户端。

```ts
const spread = db.from({ m: measurements }).select(({ m }) => ({
  populationVariance: aggregate.variancePopulationApprox(m.value),
  sampleDeviation: aggregate.standardDeviationSampleApprox(m.value).filter(m.id.gt(0)),
}));
```

四项统计不直接支持 DISTINCT。类型层在返回的 `AggregateExpression<T,K,false>` 上限制该方法，filter 保留此能力；运行时与编译器仍拒绝绕过类型构造的非法节点。AVG/COUNT/SUM 等已有聚合的默认第三泛型为 true，既有用法不变。通用 helper 应透传能力泛型，避免把调用方的限制拓宽：

```ts
function filtered<T, K extends SqlKind, D extends boolean>(
  expression: AggregateExpression<T, K, D>,
  predicate: Predicate,
): AggregateExpression<T, K, D> {
  return expression.filter(predicate);
}
```

同一 helper 返回的 AVG 仍可 DISTINCT，统计函数仍不可 DISTINCT；类型回归同时覆盖这两条路径。

需要去重样本时，先选择原值、distinct、形成 as()/CTE，再外层统计，保留去重发生在精确域还是近似域的选择。这个限制依据 [HANA 聚合表达式](https://help.sap.com/docs/PRODUCT_ID/4fe29514fd584807ac9f2a04f6754767/20a4389775191014b5a6bf2ccc0df2ed.html) 与 [MySQL 统计函数语法](https://github.com/mysql/mysql-server/blob/8.4/sql/sql_yacc.yy#L10511-L10525)，不能因为 PG 接受更多语法而把它声明为共同能力。

测试分别验证四项函数的普通聚合、条件、空集、单样本、微小 DECIMAL、分组/HAVING、累计/current-only/next-only 窗口、CTE/count/page，并与直接原生 SQL 对照。数值算法与溢出继续属于数据库：不改写成不稳定的平方和公式，不把微小负误差夹为 0，也不偷偷修改 MySQL 的 windowing_use_high_precision。[MySQL 窗口精度](https://dev.mysql.com/doc/refman/8.4/en/window-function-optimization.html)

## 驱动输出与可复现范围

PG 适配器保留文本结果，应用池应保持 `extra_float_digits > 0`，使用 PG 17 默认的最短精确浮点输出。设置为 0 或更低时，服务器可能在 codec 收到数据前舍掉有效数字；合法数字文本本身无法证明原值是否已经舍入。ORM 不修改调用方会话，也不承诺任意池设置下逐位往返。[PG 浮点输出规则](https://www.postgresql.org/docs/17/datatype-numeric.html#DATATYPE-FLOAT)

MySQL prepared statement 与 HANA SDK 的 DOUBLE 结果使用 JS number，不新增全局 parser。HANA 不保留负零位模式，共同 codec 因此将它正规化。[HANA 数值类型](https://help.sap.com/docs/PRODUCT_ID/4fe29514fd584807ac9f2a04f6754767/4ee2f261e9c44003807d08ccc2e249ac.html?locale=en-US&state=PRODUCTION&version=latest)

本地三库验证了 1.2345678901234567、Number.MAX_VALUE、最小正常双精度、Number.MIN_VALUE、1e100、1e-100 和负零的参数/列往返；这是当前 PG 17.11、MySQL 8.4.11、HANA 2.00.088 与已锁驱动的证据，不是所有版本或算术中间步骤的范围承诺。AVG 极大值仍可能在数据库中间计算溢出；错误保留，非有限结果也不能进入业务对象。

统计 fixture 验证普通/精确输入、条件与 DISTINCT、空值、移动平均、空 frame、CTE/HAVING/page，以及转换前后去重的不同结果。MySQL 在 div_precision_increment=0/30 下得到相同近似平均值；设置在 finally 恢复。PG 在事务内临时降低 extra_float_digits，确认输出损失并在事务结束后恢复。所有随机表和自有池正常清理。

PG float 数组本批未自动开放；数组的专属格式与非有限元素合同仍需独立验证。普通 JSON 的数值规则也没有随 float64 放宽。详细运行记录见 [验证证据](evidence.md)。
