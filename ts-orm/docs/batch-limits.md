# 参数预算与批量写入

每条 SQL 的实际绑定参数默认最多 65,535 个，可以通过方言实例下调：

```ts
const db = createDatabase({
  dialect: dialect("postgres", { maxParameters: 2_000 }),
  driver: postgresDriver(pool),
});
```

项目当前允许 1–65,535 的整数预算，三个数据库一致。PG 和 MySQL 另有已确认的 65,535 硬限制；HANA 的这个数字表示本项目支持范围，**不表示已经测得 HANA 引擎或 Node 客户端的总参数极限**。HANA IN 的元素限制和较大参数协议能力也不应混为一谈。[PG 限制](https://www.postgresql.org/docs/17/limits.html)、[PG Bind 协议](https://www.postgresql.org/docs/17/protocol-message-formats.html#PROTOCOL-MESSAGE-FORMATS-BIND)、[MySQL prepared statement 源码](https://github.com/mysql/mysql-server/blob/8.4/sql/sql_prepare.cc)

手写的 Dialect 可以省略 maxParameters，保持上述缺省预算；提供非法值时仍在编译入口拒绝，不只校验标准方言工厂。

## 实际绑定次数

预算在编译器唯一参数追加入口计算，不按调用方数组大小或 AST 节点个数估算。同一值可能在最终 SQL 出现多次：HANA 将条件投影为保留 UNKNOWN 的 CASE，MySQL NULLS 排序增加排序键，扩展 renderer 也可能重复引用一个操作数。CTE 与主查询、RETURNING 与写入值都计入所属的同一条语句。

超限抛出 ParameterLimitError，包含 dialect、limit 和 attempted。attempted 表示触发失败的绑定位置，不是已经算出的完整 SQL 总参数数；异常不附参数值，避免日志暴露业务数据。

## INSERT 拆分

PG/MySQL 原有策略仍是合并连续相同列形状，单组至多 100 行；HANA 保持单行语句。编译完整组时若超过参数预算，就沿连续区间二分后重新编译。每条新语句重新从第一个参数开始编号，也重新计算 RETURNING 的固定开销。

只有 ParameterLimitError 会触发拆分，其他语义或类型错误立即失败。若单行加上 RETURNING 仍超限，整份计划失败。全部计划编译成功后才借连接，因此最后一组的编译错误不会让前面的组先被写入。

行顺序和原列形状保持不变，不承诺选择最少的批次数量。扩展 renderer 应为纯函数，因为编译、count/page 和超限重新规划都可能多次调用它；参数 codec 的编码发生在构造参数节点时，不在每次拆分时重新编码。

拆成多条语句不提供自动原子性。需要全部成功或全部回滚时显式使用 transaction；自动提交下的部分完成继续由 WriteExecutionError 表达已确认结果。

SELECT、UPDATE、DELETE 和 command 不自动拆分。把一个大 IN 改成多次查询可能改变排序、分页、聚合、快照或锁的含义；改成同一 SQL 中多个 OR 也不会减少总绑定数量。需要不同查询策略时，由调用方明确选择数组参数、临时表或分批业务流程。

## 字节与网络边界

参数数量预算不保证报文大小。MySQL max_allowed_packet 是独立配置；HANA packetSize 是可增长的常用封包尺寸，packetSizeLimit 才是连接包大小上限。ORM 不根据一个通用 JSON.stringify 大小声称算出了各驱动的真实 wire size，也不会调整应用连接配置或因报文过大自动重试写入。[MySQL 大包限制](https://dev.mysql.com/doc/refman/8.4/en/packet-too-large.html)、[SAP Node.js 连接属性](https://help.sap.com/docs/SAP_HANA_CLIENT/f1b440ded6144a54ada97ff95dac7adf/4fe9978ebac44f35b9369ef5a4a26f4c.html)

业务如需要更小的字符串／二进制负载范围，可在 codec 的编码边界验证；未来通用字节预算需明确是编码后逻辑负载，不能伪装成协议大小。
