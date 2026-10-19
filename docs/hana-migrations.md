# HANA 元数据与 Code First

基于 EasyQuery 3.2.17、JBR 17、ngdbc 2.30.11 与 HANA 2.00.088.00.1760424921 验证。此文记录已经实现的迁移边界；完整蓝图的其他查询、类型和消费者验收继续进行。

## 入口与目录信息

安装 `HanaDatabaseConfiguration` 后，继续使用 `client.getDatabaseCodeFirst()`、`setMigrationParser` 和上游 `TableMigrationData`。没有第二套迁移执行框架。

```java
var codeFirst = client.getDatabaseCodeFirst();
var command = codeFirst.syncTableCommand(List.of(Order.class));
command.executeWithTransaction(arg -> {
    System.out.println(arg.getSQL());
    arg.commit();
});
```

命令在创建时冻结 SQL；回调获得与实际执行相同的预览。回调未调用 `commit()` 或显式调用 `rollback()` 时，DDL 被执行后回滚，与上游手动提交契约一致。需要纯预览而不执行时，使用 `getMigrationsSQLGenerator()` 生成 `MigrationCommand` 并读取 `toSQL()`。不要将“未提交”理解为“数据库没有执行任何 DDL”。

`client.getRuntimeContext().getService(HanaCatalog.class)` 提供只读目录访问：

| 方法 | 契约 |
| --- | --- |
| `schemas()` / `currentSchema()` | 当前用户可见的 schema；当前 schema 从实际连接解析 |
| `relations(schema)` | 精确 schema 内的表和视图，保留行存/列存与原生视图类型 |
| `find(schema, name)` | 区分表/视图；不存在返回空，权限与目录查询失败抛错 |
| `describe(schema, name)` | 不可变列、主键、有序索引、外键、注释、默认值与生成规则 |

这些方法接收原始标识符；`null` schema 表示连接当前 schema。目录查询绑定参数，探测对象时逐个引用 schema/对象名。不可见 schema 不会被解释成空库。目录不可见但对象存在的 synonym 等情况也不会被伪装为“不存在”。

`SYS.TABLE_COLUMNS.DEFAULT_VALUE` 的字符串已经解码，没有 SQL 外层引号；BOOLEAN 默认值记录为 `0`/`1`。适配器按类型比较这些信息，不把它们直接拼接为 DDL。目录中的空 identity 生成表达式也与普通计算列表达式区分。

## 创建与类型

默认创建 COLUMN TABLE，支持单个 identity、复合主键、普通/唯一索引、外键及表/列注释。所有表与索引先创建，外键最后执行，因此被引用表不必排在输入列表前面。

| Java 类型 | 缺省 HANA DDL |
| --- | --- |
| Boolean/boolean | BOOLEAN |
| Byte/byte、Short/short | SMALLINT；避免 HANA TINYINT 无符号造成负数丢失 |
| Integer/int、Long/long | INTEGER、BIGINT |
| Float/float、Double/double | REAL、DOUBLE |
| BigDecimal | DECIMAL(16,2)，可用 length/scale 指定 |
| String、UUID、byte[] | NVARCHAR(255)、NVARCHAR(36)、VARBINARY(255) |
| LocalDate、LocalTime、LocalDateTime | DATE、TIME、TIMESTAMP |
| java.sql.Date/Time/Timestamp、java.util.Date | 对应 DATE/TIME/TIMESTAMP |

primitive 保留上游默认值规则，boolean 使用 FALSE。wrapper 不自行增加默认值。显式 `dbType` 使用 HANA 类型名，校验长度与 precision/scale；不接受 MySQL 的整型显示宽度或 `TIMESTAMP(3)`。裸 DECIMAL 保留 HANA 浮点十进制语义。DDL 中省略字符串长度的含义与 CAST 不同，二者独立渲染。

`generatedSQLColumnGenerator` 的 sequence 列不会被错误创建成 identity；sequence 本身仍通过明确的部署 DDL 创建。转换器的完整存储类型推导、Instant 和完整 LOB 生命周期属于后续类型验收，不能由上述默认映射推断全部完成。

`HanaForeignKeyMigrationData` 在上游迁移数据基础上补充 `targetSchema`；null 表示连接当前 schema。实体注解会保留目标实体的 schema 和 `@ForeignKey.action`。普通上游 `ForeignKeyMigrationData` 没有该字段，沿用当前表的 schema。不要把带点号的原始名称当作拼接后的限定名。

## 受控增量与拒绝边界

支持新增列/索引/外键、表和列重命名、同族长度扩宽、整数扩宽、DECIMAL 不减少整数或小数位的扩宽、REAL 到 DOUBLE、default/nullability/注释变更。重命名预览读取旧对象的目录，执行语句指向新对象。原有行不会通过复制表或重新插入来迁移。

已存在且定义一致时不产生迁移。已有的额外列、索引和外键不会自动删除。缩窄、主键替换、生成规则变更、同名但定义不同的索引/外键等需要显式审阅的迁移命令，不在自动同步时静默执行。显式 `dropTableCommand` 使用 RESTRICT；有关联依赖时由数据库拒绝，不级联删除其他对象。

当前明确限制：

- 不对视图运行表迁移，不自动创建 HANA 租户或升级 SAP 产品 schema。
- HANA 普通索引无对应注释属性，创建带索引注释的定义会明确拒绝。
- revision 88 的原生 `ALTER TABLE ... ALTER ("odd""name" NVARCHAR(40))` 报 257，置于 DDL 事务时可能包装成 129。hdbsql 已独立复现。含双引号列名可创建/读取目录；需要 ALTER 其定义时在生成计划阶段拒绝，避免修改到中途才失败。未用临时改列名或重建表绕过，防止破坏依赖对象。
- 多表相互引用的重命名、约束替换和带依赖对象的删除仍需明确迁移计划；不声称自动推断所有数据库对象依赖。

自定义 `MigrationCommand` 需每个对象提供一条 JDBC 语句。适配器自己的复合命令保留语句列表，执行时不按分号拆分 SQL；字符串默认值和注释中的分号不会被误当成新命令。

## 事务与连接池

HANA DDL 默认自动提交。适配器仅在新开的 Code First 事务、任何用户 SQL 执行前设置 `SET TRANSACTION AUTOCOMMIT DDL OFF`，继续复用上游 ConnectionManager、事务回调和 JDBC executor。预览回调内的 EasyQuery DML 与 DDL 使用同一条连接并共同提交/回滚。

`HanaEasyConnection` 继承上游连接包装，在原生 commit/rollback 后、归还连接池前恢复先前的 DDL 模式；失败路径先回滚再恢复。恢复失败的物理连接会被 abort，不能带着未知状态回到池里。其他 CRUD 不改变 DDL 模式。

`executeWithEnvTransaction` 在已有事务中要求 `autoCommit=false` 且 DDL 模式已为 OFF，适配器不会中途切换，因为切换模式本身会隐式提交现有 DML。外部事务拥有者负责设置和恢复原始模式；没有已有事务时该入口使用一个独立的上游事务并提交。Spring 专项集成仍在 G6 验证，不能仅据默认 ConnectionManager 的测试宣称已完成。

标准 JDBC 没有 DDL 模式 getter，因此本模块对 ngdbc 添加可选依赖，仅使用公开 `unwrap(ConnectionSapDB.class)`/`isDDLAutoCommit()`，不反射私有字段。普通离线安装、类型解析、DDL 生成不需要 SAP 驱动；真实连接由应用提供驱动。SQL/事务依据见 [HANA Platform 2.0 SQL Reference](https://help.sap.com/doc/9b40bf74f8644b898fb07dabdd2a36ad/2.0.08/en-US/SAP_HANA_SQL_Reference_Guide_en.pdf)，入口契约对照 [EasyQuery Code First](https://github.com/xuejmnet/easy-query-doc/blob/main/src/code-first/api.md)。

## 验证入口

- `HanaMigrationTest`：离线类型/精度、sequence 与 identity 区分、外键 action/schema、移除 ngdbc 的隔离类加载。
- `HanaCatalogIT`：两个 schema 的同名对象、quoted 标识符、列/主外键/索引/注释、不可变结果和非管理员权限。
- `HanaCodeFirstIT`：创建/幂等/增量、保留原行、外键顺序和级联行为、失败回滚、回调 DML、池复用、非管理员和原生 ALTER 限制。
- `HanaDdlTransactionIT`：原生 JDBC DDL 回滚与模式 getter 的独立证据。

测试均使用独立的随机 schema/用户，finally/资源作用域清理；不会清空已有开发数据库。当前提交对应的完整 CI 证据见 [执行记录](execution.md)。
