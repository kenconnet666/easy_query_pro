# MySQL TIME 的时长视图

`MySqlTypes.durationTime(fsp)` 明确选择 elapsed time，Java 表示为 Duration，fsp 范围 0–6。它支持负值与超过 24 小时的时长；原有 LocalTime 继续表示一天内的钟点。

```java
var type = MySqlTypes.durationTime(6);
query.queryable(Job.class).selectColumn(j ->
        MySqlDsl.of(j).view(j.elapsed(), type).toSeconds());
```

完整 TIME 范围为 ±838:59:59.000000。prepareValue 与 value 检查容量和所选小数位，不提前舍入；例如 TIME(3) 拒绝一微秒，TIME(6) 拒绝一纳秒。负时长按 Duration 的规范化秒/纳秒表示检查，无需对 Long.MIN_VALUE 取绝对值。

## 配置和读写

安装 MySqlExtensions.configure，并在首次实体元数据使用前注册 handler：

```java
MySqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());

@Column(dbType = "TIME(6)")
private Duration elapsed;
```

读取复用 Connector/J 的 `getObject(index, Duration.class)`。该驱动支持 TIME 的完整时长范围，java.sql.Time/LocalTime 无法表达这个范围。通过公开 metadata SPI 补齐 EasyQuery 3.2.17 缺少的 Duration 标量类型，仅注册 Duration 的 handler，保留应用已有注册及 LocalTime 映射。

9.7.0 的客户端预处理 Duration 编码会在零值或秒数末尾保留 S，严格 INSERT 会拒绝；服务端预处理则需要原生 TIME 参数，以免字符串在 CAST 前被错误转换。因此 handler 通过标准 JDBC wrapper API 判断实际 ServerPreparedStatement：二进制语句保留 setObject(Duration)，客户端语句绑定明确的 `[-]hhh:mm:ss.ffffff` 文本。连接池代理和 rewriteBatchedStatements 的实库组合已验证。

Connector/J 是可选依赖，应用仍选择自己的驱动。当前验证基线为 MySQL 8.4.11 / Connector/J 9.7.0。无需修改服务端全局设置，原语句与事务执行仍由 EasyQuery 管理。

## 视图、转换和运算

| 入口 | 规则 |
| --- | --- |
| view(column, type) | 保留完整列的 Java 映射；已知 TIME 精度不匹配时拒绝 |
| nativeCast(column, type) | 显式生成 TIME(fsp) CAST，结果 Java 类型为 Duration |
| value(duration, type) | 范围及精度检查后的参数；null 表示 SQL NULL |
| plus / minus / negated | MySQL 原生 ADDTIME/SUBTIME 运算，返回时长表达式 |
| equalTo / lessThan | 返回可投影/过滤的 Boolean 表达式 |
| toSeconds | 返回带微秒部分的 BigDecimal 数字表达式 |
| addToDateTime / subtractFromDateTime | 与 LocalDateTime 组合，返回 LocalDateTime |

时长 view 不能保留已知 LocalTime/java.sql.Time 的钟点读取方式；需要改变 Java 表示时使用 nativeCast。完整值可直接传入既有更新 setter；提取秒数后，使用既有数字运算与聚合。

MySQL 的 TIME_TO_SEC 会丢弃小数秒。toSeconds 组合整秒与带符号的 MICROSECOND，以十进制乘法保留六位精度，不依赖 div_precision_increment；负的一微秒也能准确返回 -0.000001。

```java
query.updatable(Job.class).setColumns(j -> {
    var mysql = MySqlDsl.of(j);
    j.elapsed().set(mysql.view(j.elapsed(), MySqlTypes.durationTime(6)).negated());
}).where(j -> j.id().eq(jobId)).executeRows();
```

原生 TIME 运算超范围可能饱和到上下界并产生数据库 warning；nativeCast 也保留数据库的舍入/裁剪行为。要进行超出 TIME 容量的汇总，应先转秒数继续做数值运算，不把饱和结果当成无界 Duration 算术。实体直接写入由 handler 检查完整 TIME 容量/微秒精度；比六位更低的实际列精度仍遵循数据库行为，使用对应 value 描述可提前拒绝精度损失。

## 投影与验收

计算后的完整值保留原列读取器及 ValueConverter。公共 MappedValueExpression 通过 EasyQuery 的 ColumnReader SPI 处理 Draft，保留 APT 的标量载体，避免把业务 record/List 重新当作实体构造。JSON、数组和 interval 同时覆盖了该修复，部分标量提取仍使用自己的结果类型。

本地验证包含范围两端、负一微秒、27 小时、NULL、零值、两个预处理模式、Hikari 代理、批量重写、精确秒数、日期时间加减、原生饱和、钟点显式转换和更新回滚。入口为 MySqlDurationTypeTest、MySqlDurationIT、DslCompilationTest；完整组合与资源性能门槛继续由 G4.6/G6 验收。

依据：[MySQL TIME 类型](https://dev.mysql.com/doc/refman/8.4/en/time.html)、[MySQL 日期时间函数](https://dev.mysql.com/doc/refman/8.4/en/date-and-time-functions.html)。
