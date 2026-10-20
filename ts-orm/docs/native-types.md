# 原生类型与泛型挂载

共同能力继续使用 `types`。原生 SQL 类型使用带命名空间的 kind，例如 `pg:uuid`；这个名称只决定表达式族，不会直接拼成 SQL 类型名。JavaScript 的值类型 T、SQL kind K 和运行时 codec 是三个明确的契约。

```ts
const orderId = postgresTypes.uuid().map({
  identity: Symbol("OrderId"),
  to: (id: { value: string }) => id.value,
  from: (value) => ({ value }),
});
const orders = table("orders", {
  id: column(orderId),
  parentId: column(orderId).nullable(),
});
```

UUID 字符串使用标准 8-4-4-4-12 十六进制形式，接受大写并规范化为小写。输入不限制 UUID 版本或 variant；不会生成 ID，也不把大括号、缺省连字符等 PostgreSQL 额外输入写法冒充本工厂承诺。数据库始终输出标准形式。[PostgreSQL 17 UUID](https://www.postgresql.org/docs/17/datatype-uuid.html)

`Expression<string, "pg:uuid">` 不能调用 upper/add，也不能直接和 text 表达式比较。UUID 支持 PostgreSQL 原生相等与排序；这不意味着 PostgreSQL 提供 min(uuid)/max(uuid)，共同 MIN/MAX 仍有自己明确的类型范围。需要数据库转换时使用 `nativeCast`，不能只改变泛型声明。

## 应用原生定义

应用仍用同一个 DataType 构造器，不需要全局注册或 module augmentation：

```ts
const definition: NativeStorage = {
  identity: Symbol("custom-uuid-storage"),
  dialects: {
    postgres: {
      parameter: ["CAST(", " AS UUID)"],
      cast: ["CAST(", " AS UUID)"],
      equality: true,
      ordering: true,
    },
  },
};
const custom = new DataType<string, "app:uuid">(
  { kind: "app:uuid", native: definition },
  postgresTypes.uuid().codec,
);
```

参数和 CAST 的两个片段包住一个操作数，框架只插入该操作数一次。**片段是可信扩展代码，不能来自用户输入，也不能自行放入额外绑定占位符。** 它们没有经过通用 SQL 解析或语法验证。cast 可省略，表示可以按声明协议绑定，但不提供这个数据库的显式 CAST。比较能力默认关闭。

构造器快照整个 native 定义、方言 entry 和片段 tuple；后续修改原配置不会改变已有查询。相同物理配置的工厂应复用稳定 identity；不同配置用不同 identity。兼容检查还比较实际片段与能力，防止复用 identity 却改变 SQL 表示。codec identity 另行保留完整映射链。

检查覆盖实际引用的列、参数、函数返回、CAST、低层 Expression 构造和结果投影。只有 SELECT 原生列也会检查方言。相等比较、IN、NULLIF、GROUP BY、窗口分区、DISTINCT/UNION 与 ORDER BY 按输出类型核对能力；IS NULL、CASE/COALESCE 不凭空要求原值可排序。显式 CAST 成共同 text 后，外层使用 text 的能力，但内层仍须支持当前数据库。

LEFT JOIN、CASE/COALESCE、CTE、UNION 和值窗口继续携带已有 DataType/codec。UNION 和赋值需要相同存储与 codec；比较不选择结果 reader，因此共同 text 长度或 DECIMAL 精度可以不同，但 kind 与完整业务 codec 链仍要相同。不同业务单位不能只因同为 string 而混用。

## CAST 与 NULL

`nativeCast(value, type)` 的结果统一为 `T | null`。非 NULL 输入也可能按数据库规则转换成 NULL；它不承诺原值无损，也不会用应用解析来替代数据库转换。要写入非空列，调用方需要用合适的 COALESCE 默认值明确处理 NULL。

MySQL 8.4 本地实测 `NO_ZERO_DATE` 模式下，常量 `CAST('0000-00-00' AS DATE)` 返回 NULL，但服务端预处理 `CAST(? AS DATE)` 绑定相同文本返回零日期字符串；绑定非法文本 `not-a-date` 则返回 NULL。此处保留真实差异，不从常量示例推导参数的一律行为。后续共同日期 codec 会校验可移植日期域，原生 CAST 返回域外非空值时仍可能解码失败。[MySQL CAST 规则](https://dev.mysql.com/doc/refman/8.4/en/cast-functions.html)

调用方的 `mount(factory)` 和 client extension namespace 继续用于增强表达式 API。新的 DataType 只定义存储与值协议；数据库函数的结构化 renderer 仍通过已有 call/sqlFragment 组合，避免另起一套查询或执行系统。
