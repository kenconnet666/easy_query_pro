# PostgreSQL 数组与泛型挂载

数组使用 `postgresTypes.array(elementType)`，仍然是普通 DataType。元素可以是共同 integer、bigint、decimal、text、boolean、date、localDateTime，以及内建 UUID。元素上的业务 `.map()` 继续生效；数组不会把 BIGINT 转成 number，也不会把 DECIMAL 或日期经 JS Date 读取。

```ts
const documents = table("document", {
  id: column(types.integer()),
  tags: column(postgresTypes.array(types.text(100))),
  reviewers: column(postgresTypes.array(postgresTypes.uuid().nullable())).nullable(),
});

const report = db
  .from({ d: documents })
  .where(({ d }) => d.tags.mount(pgArray).contains(["finance", "approved"]))
  .select(({ d }) => ({
    id: d.id,
    tags: d.tags,
    reviewerSlots: d.reviewers.mount(pgArray).cardinality(),
  }));
```

`mount(pgArray)` 增加 contains（`@>`）、overlaps（`&&`）、cardinality 和 anyEquals。原表达式的关系身份、结果类型和 codec 不变，派生表/CTE 后按需重新挂载；没有全局修改所有表达式的方法表。contains/overlaps 可接业务数组或相同存储、相同完整 codec 的数组表达式。anyEquals 当前只接收非空业务元素值，通过单元素数组绑定后取 `[1]`，复用元素转换，不接受标量 Expression。

| 声明或输入                          | 业务含义                             |
| ----------------------------------- | ------------------------------------ |
| `array(types.integer())`            | 非空数组，元素不允许 NULL            |
| `array(types.integer().nullable())` | 非空数组，可有 NULL 元素             |
| `array(types.integer()).nullable()` | 整列可为 SQL NULL，元素仍不允许 NULL |
| `[]`                                | 空数组，cardinality 为 0             |
| `[null]`                            | 一个 NULL 元素，cardinality 为 1     |
| `null`                              | SQL NULL 数组，cardinality 为 NULL   |

NULL、空数组和重复元素遵循 PostgreSQL 原生规则。空数组包含空数组，但不与任何数组重叠；`[NULL] @> [NULL]` 为 false，而数组相等比较可以为 true。`7 = ANY([NULL])` 为 NULL，`7 = ANY([])` 为 false。contains 不按多重集合计数，`[1]` 包含 `[1,1]`。不能用 JavaScript 的 includes 或长度推导这些 SQL 结果。参见 [数组函数](https://www.postgresql.org/docs/17/functions-array.html) 与 [ANY 比较](https://www.postgresql.org/docs/17/functions-comparisons.html#FUNCTIONS-COMPARISONS-ANY-SOME)。

当前合同只接受一维、默认下界的数组。读取多维或带 `[0:1]=...` 等维度前缀的结果会明确失败，避免默默丢掉数据库维度/下界。声明不创建约束；外部写入仍可制造不符合 codec 的数据。JSON 元素和任意扩展原生类型没有自动纳入：前者需先明确 JSON null 与 SQL NULL，后者需要自己的元素 SQL 表示，不能只凭一个 kind 名字猜测。

元素 `.map()` 可将 UUID 映射为业务 ID 对象。对整个数组 `.map()` 仍可用于读写，但不能直接 mount(pgArray)：整体映射可能限制长度或改变列表含义，而 anyEquals 需要能够编码任意单元素列表。将业务转换放到元素类型上，或定义符合业务合同的独立挂载工厂。

## 编码、解析与连接状态

写入先复制并逐元素编码，再把不可变标量数组交给 pg；空洞/undefined、错误元素类型和不符合精度的数值提前失败。一个数组是一个绑定参数，65,536 个元素也不是 65,536 个参数；网络报文和服务器资源仍有独立限制。pg 的数组编码行为见 [官方查询说明](https://node-postgres.com/features/queries)。

读取复用 MIT 的 [postgres-array 3.0.4](https://github.com/bendrucker/postgres-array/tree/v3.0.4)，是核心包唯一直接运行时依赖。只在进入解析器前检查一维结构与引号闭合，实际反转义由依赖完成；NULL 元素也必须经过元素 codec。PG 使用六种 ASCII 空白，NBSP、EM SPACE、BOM 等合法 Unicode 元素不能被 JavaScript 的 `\s` 误拒。

PG `array_nulls=off` 会把未加引号的 NULL 元素解释成文本，因此适配器发现参数数组包含 NULL 时，会在同一连接先执行 `SHOW array_nulls`；不是 on 就拒绝业务语句。它不会悄悄修改应用会话。检查与业务语句作为一个顺序操作排队，避免同租约的 SET/COMMIT 插进二者之间；状态不缓存。字符串 `"NULL"` 仍可正常使用。具体服务器规则见 [数组输入输出](https://www.postgresql.org/docs/17/arrays.html#ARRAYS-IO)。

本地契约覆盖精确 BIGINT/DECIMAL、微秒时间、UUID 业务映射、特殊字符、NULL 真值、CTE/UNION、参数预算、array_nulls 变化及维度拒绝。测试只删除自己创建的随机表，完整矩阵见 [验证记录](evidence.md)。
