# 共同日期与本地日期时间

`types.date()` 和 `types.localDateTime()` 保存日期／钟表字段，使用字符串承载，不经过 JavaScript Date。它们适合生日、账期和本地业务时间；带时区时间点是不同的类型，当前不隐式转换。

```ts
const events = table("events", {
  id: column(types.integer()),
  day: column(types.date()),
  occurred: column(types.localDateTime()).nullable(),
});
await db
  .insert(events)
  .values({
    id: 1,
    day: "2024-03-10",
    occurred: "2024-03-10 02:30:00.123456",
  })
  .execute();
```

共同年份范围为 1000–9999，日期按真实 Gregorian 闰年规则校验。拒绝零日期、非法闰日、24:00、闰秒、Z／时区偏移和 Date 对象。日期固定 `YYYY-MM-DD`；本地日期时间输入允许空格或 T、零至六位小数秒，输出统一为 `YYYY-MM-DD HH:mm:ss.ffffff`。

例如纽约夏令时跳过的 `2024-03-10 02:30:00.123456` 仍保留这些业务字段，不自动改成 03:30；重复时刻也不猜测是哪一个真实时间点。微秒不会经过 Date 的毫秒精度路径。node-postgres 默认 Date 解析受进程时区影响且会丢失微秒，本适配器已有的查询局部文本 parser 避开了这条路径。[node-postgres 日期类型](https://node-postgres.com/features/types)

## 表定义与参数

| 类型          | PostgreSQL                     | MySQL       | HANA      |
| ------------- | ------------------------------ | ----------- | --------- |
| date          | DATE                           | DATE        | DATE      |
| localDateTime | TIMESTAMP(6) WITHOUT TIME ZONE | DATETIME(6) | TIMESTAMP |

schema 不执行建表；上表是应用 DDL 应匹配的物理类型。MySQL TIMESTAMP、PostgreSQL TIMESTAMPTZ 不属于这个本地字段合同。

PG/MySQL 参数明确 CAST 成对应类型；HANA 使用带显式格式的 TO_DATE / TO_TIMESTAMP，输入已经过 codec 正规化。HANA 的原生 TIMESTAMP 精度是 7 位，不能声明成 TIMESTAMP(6)。原生读取发现超出微秒的非零部分会失败，不能悄悄截断。[HANA 日期时间类型](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)

## 读取环境

PostgreSQL 需要 ISO DateStyle，MDY/DMY 顺序不影响 ISO 布局。参数使用完整 ISO 字段，不依赖当前日月顺序；结果若被服务器设置成 SQL、German 或 Postgres 布局，codec 明确失败，不猜测 `01/02` 的含义，也不修改应用会话。BC、infinity 和超出共同年份范围的值也拒绝。[PostgreSQL 日期时间输入与输出](https://www.postgresql.org/docs/17/datatype-datetime.html)

没有用 TO_CHAR 改写投影来隐藏这些条件。DATE/TIMESTAMP 继续作为原生值参与 DISTINCT、GROUP BY、ORDER BY、JOIN、CTE、UNION、MIN/MAX 和值窗口；这样不会改变既有排序或去重规则。输出字符串是在驱动返回后才解码的。

MySQL 适配器使用 dateStrings，不经进程时区或 Date。非标准／零日期的非 NULL 数据会在 codec 中失败；原生 CAST 的数据库规则仍可能返回 NULL 或域外值，见 [原生类型转换](native-types.md)。

HANA Node 2.30.27 实测返回日期字符串和带 9 位小数秒的 timestamp 字符串。读取允许第 7–9 位全部为零时无损规范化；任一额外位非零都抛出 RangeError，并保留在 projection 错误的 cause 中。`isoTimestampOutput: true` 的 T 分隔和默认空格均已验证。

使用共同日期类型的 HANA 应用池请设置：

```ts
const pool = hana.createPool(
  { serverNode, uid, pwd, emptyTimestampIsNull: false },
  { poolCapacity: 4, maxConnectedOrPooled: 4 },
);
```

HANA 的 EMPTY DATE/TIMESTAMP 是非 SQL NULL 的特殊值。SDK 默认 `emptyTimestampIsNull: true` 会在 codec 看到结果前把它变成 null，ORM 无法事后恢复区别。显式 false 后，EMPTY 即使经过 nullable codec 也会因共同日期范围不合法而失败，真正 SQL NULL 正常保留。池属于应用，适配器不暗中替换连接属性。[SAP Node.js 连接属性](https://help.sap.com/docs/SAP_HANA_CLIENT/f1b440ded6144a54ada97ff95dac7adf/4fe9978ebac44f35b9369ef5a4a26f4c.html)

更宽年份、时间点、HANA 100ns 和 MySQL 超过 24 小时的 TIME 应采用不同的显式原生类型，不能在共同类型中用隐式丢精度换取表面兼容。
