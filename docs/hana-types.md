# HANA JDBC 类型契约

目标为 EasyQuery 3.2.17、JBR 17、ngdbc 2.30.11、HANA 2.00.088.00.1760424921。类型目录与完整验证仍按蓝图推进，不能将一类类型的通过当成全部类型验收完成。

## 日期、时间与时间点

| Java 类型 | 缺省存储 | 绑定与读取规则 |
| --- | --- | --- |
| `LocalDate` | DATE | 保留年月日，不根据 JVM 时区改变日期 |
| `LocalTime` | TIME | 整秒；非零纳秒在绑定前拒绝 |
| `LocalDateTime` | TIMESTAMP | 保留本地日期时间；100ns 精度，无法表示的余数明确拒绝 |
| `Instant` | TIMESTAMP | 将 UTC 日期时间存入无时区列，读取时明确按 UTC 还原 |

不依赖系统默认时区解释上述四种类型。绑定使用 UTC Calendar，结果在进入上游行映射/排序/内存聚合之前读取为逻辑时间值。没有向 SQL 文本追加日期格式转换，也没有增加数据库往返。

实际复现表明，ngdbc 的 `getObject(LocalDateTime.class)` 和部分 `getString()` 也会经过旧日期转换：柏林 `2024-03-31 02:30` 被归一化为 `03:30`，Apia `2011-12-30` 被推到次日。因此仅调用 JDBC 4.2 的类型重载无法解决问题。适配器使用带 Calendar 的原始 getter，并通过公开 Statement 配置钩子获取分片查询的 JDBC 结果；不反射上游私有字段。上游仍拥有 Statement/ResultSet 的关闭过程。

EasyQuery 3.2.17 的基础标量识别未包含 `Instant`。`HanaEntityMetadataManager` 只补充该类型，其余实体、Map、导航和缓存保持上游实现。`Instant` 的实体字段、SQL 标量、既有 Proxy 筛选/投影及 Code First 类型已真实验证。

`java.util.Date` 和 `java.sql` 类型保留旧语义，不自动改成 UTC 时间点。无类型 Map 仍返回上游的 `java.sql` 类型；如果 JVM 时区使 Timestamp/Date 无法表示原始本地日期，适配器报 SQLSTATE 22008，并提示使用 `java.time` 投影，不静默归一化。推荐新实体以表中四种类型明确表达意图。

JDBC Calendar 参数的用途可对照 [SAP HANA Client Interface Programming Reference](https://help.sap.com/doc/e48ad53de2444b5f987c75288c42758a/2.0.04/en-US/SAP_HANA_Client_Interface_Programming_Reference_en.pdf)；上述 ngdbc 2.30.11 行为由本项目测试独立验证。

## 历法边界

HANA SQL 在 1582 年切换历法。目标实例上的原生 hdbsql 返回：

```sql
SELECT TO_NVARCHAR(ADD_DAYS(TO_DATE('1582-10-04'), 1), 'YYYY-MM-DD') FROM DUMMY;
-- 1582-10-15
SELECT TO_NVARCHAR(TO_DATE('1582-10-10'), 'YYYY-MM-DD') FROM DUMMY;
-- 1582-10-20
```

因此适配器保留原始日期标签，但不把 Java 的连续公历规则强加给 HANA。绑定年份限定 0001–9999，`1582-10-05` 至 `1582-10-14` 在绑定前拒绝。读取 Java 无法表示的 Julian-only 闰日，例如 HANA 的 `1500-02-29`，同样明确拒绝。不能将 Java/SQL 在这些历史日期上的日期运算视为完全等价。

已验证 `0001-01-01`、`1500-02-28`、切历前后日期和 `9999-12-31`，分别检查数据库实际收到的日期文本与读取结果，避免两次相反的转换错误抵消后造成“往返正确”的假象。

## 验证与未完成项

`HanaTemporalBindingIT` 覆盖六组行为：DST 缺口、跳过整日的时区、三个时区中的 UTC Instant/精度、实体批量插入/更新/投影/NULL、历法边界，以及 legacy Map 与上游 `DefaultMemoryResultSetRow`。后者验证值在聚合之前已经保留；完整多库分片/导航组合仍属于 G2.5，不能以这一测试替代。

已有 Byte 范围、Unicode/CAST 的证据见 [函数契约](hana-functions.md)。DDL 映射与迁移规则见 [迁移说明](hana-migrations.md)。完整 UUID/二进制、LOB 流生命周期及自定义值转换器协作继续实施，未标记为完成。
