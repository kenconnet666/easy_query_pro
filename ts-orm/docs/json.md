# JSON 文档、SQL NULL 与精确文本

共同 JSON 使用同一 DataType 管道：PostgreSQL 为 JSONB，MySQL 为 JSON，HANA 为 NCLOB。schema 只声明类型，不创建列或 JSON 约束。三库驱动均保留原始结果文本，最后由投影 codec 读取；不依赖全局 driver parser。

## 文档与空值

```ts
function validateSettings(value: unknown): { enabled: boolean } | null {
  if (value === null) return null;
  if (typeof value === "object" && "enabled" in value && typeof value.enabled === "boolean") {
    return { enabled: value.enabled };
  }
  throw new TypeError("Expected settings or JSON null");
}

const settings = types.json(validateSettings);
const profiles = table("profile", {
  id: column(types.integer()),
  settings: column(settings).nullable(),
});

await db
  .insert(profiles)
  .values([
    { id: 1, settings: { enabled: true } },
    { id: 2, settings: jsonNull }, // JSON 文档 null，数据库 IS NULL 为 false
    { id: 3, settings: null }, // SQL NULL，必须先声明 nullable()
  ])
  .execute();
```

`jsonNull` 是从包导出的唯一 symbol，只用于文档顶层。JSON 对象内的 null 仍是普通 JS null；嵌套 jsonNull 会拒绝。普通字符串 `"null"` 则是一个 JSON 字符串，既不等于 sentinel，也不等于 SQL NULL。

validator 接收普通 JSON 值，因此写入 jsonNull 时先传 null 给 validator。它仍决定是否允许这个文档，严格对象 validator 不会被 sentinel 绕过。读取时将 validator 返回的顶层 null 转为 jsonNull；SQL NULL 在 DataType 外层处理，不调用 validator。`.map()` 可以继续把文档映射成业务对象，`.nullable()` 只增加 SQL NULL。

这是对早期 JSON codec 的明确修正：原来 JSON 文本 null 与 SQL NULL 都可能变成 JS null，读后再写会改变含义。返回 `T | null` 的 validator 对应结果现在为 `NonNullable<T> | JsonNull`；只有再调用 `.nullable()` 才允许普通 null。`types.json(value => value)` 的 unknown 返回也不会静态放开 null/undefined，但具体对象结构仍应由应用 validator 收窄。

## 序列化与数字边界

文档模式接受普通 JSON 数据：字符串、布尔值、有限 number、null、数组和普通对象。整数必须在 JavaScript 安全整数范围内。编码将 validator 输出创建为纯数据快照，再 stringify；拒绝 undefined、函数、symbol、bigint、数组空洞、循环、可枚举访问器、附加数组属性及 Date/Map/类实例。不会调用对象的 toJSON 或 getter 来猜测业务转换。类实例应通过 `.map()` 显式转换。

同一个普通对象出现在两个分支允许序列化；只有当前祖先链重复才是循环。`__proto__` 作为 JSON 键按普通数据写入，不改变快照原型。普通对象非可枚举属性不属于文档字段。

文档模式读取使用 JSON.parse，之后在业务 validator 前拒绝非有限 number 和不安全整数。**这不等于所有 JSON 数字无损**：长小数仍可能舍入，极小指数可能下溢。业务需要精确数字时使用下面的文本 codec，不把已经舍入的 number 转回字符串充当恢复。

## 原始 JSON 文本

```ts
const exact = types.jsonText();
const events = table("event", {
  id: column(types.integer()),
  payload: column(exact).nullable(),
});
await db
  .insert(events)
  .values({
    id: 1,
    payload: '{"amount":0.1234567890123456789,"id":9007199254740993}',
  })
  .execute();
```

jsonText 只检查 JSON 语法并返回收到的原字符串。语法检查产生的 JS number 不会参与结果或重新序列化，因而 ORM 不额外损失数字文本。输入 `"null"` 是 JSON null 文本，`'"null"'` 是 JSON 字符串文本，普通 JS null 仍是 SQL NULL。它的 SQL kind 仍是 json，不能因 JS 载体为 string 就调用 UPPER 等文本方法。

这个保证止于 codec：PG JSONB 会规范空白、键顺序及重复键；MySQL JSON 有整数与 double 等内部表示，不承诺保留任意精度小数或原始字节。HANA NCLOB 也不会自动替应用创建 JSON 约束，外部写入坏 JSON 时读取会报错。参见 [PG 17 JSON 类型](https://www.postgresql.org/docs/17/datatype-json.html) 与 [MySQL 8.4 JSON 数字解析](https://github.com/mysql/mysql-server/blob/8.4/sql-common/json_dom.cc#L448-L473)。

应用需要 Decimal/BigInt 文档时，可以在 `jsonText().map(...)` 接入自己选择的精确解析器；核心不强制另一套数字对象。文档型 JSON 与原文 JSON 有不同 codec identity，UNION/CASE 不会把两种读取合同当成相同结果。

本设计参考 EasyQuery 的 ValueConverter/ColumnReader 分工，以及 [Prisma 的 JSON null 与数据库 NULL 区分](https://www.prisma.io/docs/orm/v7/prisma-client/special-fields-and-types/working-with-json-fields#using-null-values)。本项目已有 `.nullable()`，因此不再加入另一组 DbNull/AnyNull 标记，也未复制生成器或 JSON 路径 DSL。

## 比较、分组和统计

共同 API 保留类型，但不能改变底层存储能力。以下限制在编译时检查，CASE、COALESCE、派生表和 CTE 不会抹掉 JSON 类型：

| 操作                              | PostgreSQL JSONB | MySQL JSON | HANA NCLOB             |
| --------------------------------- | ---------------- | ---------- | ---------------------- |
| 读取、IS NULL、UNION ALL          | 支持             | 支持       | 支持                   |
| 等于、不等于、NULL-safe 比较      | 支持             | 支持       | 拒绝                   |
| IN                                | 支持             | 拒绝       | 拒绝                   |
| GROUP BY、DISTINCT、UNION 去重    | 支持             | 支持       | 拒绝                   |
| 整份文档 ORDER BY                 | 原生规则         | 拒绝       | 拒绝                   |
| COUNT(document)、条件及窗口 COUNT | 支持             | 支持       | 支持，转为长度标量计数 |

PG/MySQL 的 `document.eqNullSafe(jsonNull)` 匹配 JSON null，`.eqNullSafe(null)` 匹配 SQL NULL；结果始终是 boolean。jsonText 使用相同 SQL 语义，但其参数是 JSON 文本，例如 `.eqNullSafe("null")`。普通 eq/ne 遇到 SQL NULL 仍返回 UNKNOWN。

COUNT 只跳过 SQL NULL，JSON null 和空对象均计入。HANA 普通聚合允许 `CASE WHEN lob IS NOT NULL`，窗口聚合却拒绝同一 LOB 参与方式，因此统一先调用 LENGTH：NULL 对应 NULL，非 NULL 的长度即使为零也会计入。不会截断成有限 NVARCHAR，也不比较文档长度是否相等；COUNT DISTINCT 仍拒绝。

MySQL 官方明确不支持 JSON IN，非标量文档排序也不受支持；字符串排序键还受 max_sort_length 限制，因此通用 ORDER BY 拒绝隐含的整份 JSON 排序。可通过显式标量投影选择业务排序依据。MySQL 的比较和分组仍保留，未因排序限制一并关闭；PG 与 MySQL 的 JSON 类型排序优先级也没有统一承诺。参见 [MySQL 8.4 JSON 比较与排序](https://dev.mysql.com/doc/refman/8.4/en/json.html) 和 [HANA LOB 类型限制](https://help.sap.com/docs/PRODUCT_ID/4fe29514fd584807ac9f2a04f6754767/c374aca91944460b834ef1d5ceb82acf.html)。

三库契约验证真实 JSONB/JSON/NCLOB 的往返、IS NULL、业务字符串、嵌套值、精确 64 位整数文本、LEFT JOIN、CTE/page、COUNT 和更新，PG/MySQL 另验证 JSON NULL-safe 比较与分组；记录见 [验证证据](evidence.md)。
