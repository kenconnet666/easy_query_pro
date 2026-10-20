# HANA 参数化查询来源

`HanaCalculationView<P,T>` 将已部署的计算视图接入原 EntityQueryable。它负责对象名称引用、输入参数声明和绑定；部署、权限、连接池、事务、执行器与结果映射继续使用既有组件。

## 声明输入和输出

```java
var name = HanaInput.required("P_NAME", String.class);
var view = HanaCalculationView.of(
        "REPORT_SCHEMA",
        "SALES_CALC",
        SaleViewProxy::createTable,
        name);

var rows = view.query(entityQuery.getEasyQueryClient(), name.bind("O'Reilly 中文"));
var page = rows.cloneQueryable()
        .where(r -> r.amount().gt(new BigDecimal("100")))
        .orderBy(r -> r.id().asc())
        .toPageResult(1, 20);
```

client 需要安装 `HanaExtensions`。schema 和视图名作为两个完整标识符分别引用；名称中包含点、空格或引号也不会变成额外 SQL 片段。输入名使用模型中的裸名称，例如 `P_NAME`，不要自行加 `$$`。值生成 JDBC 占位符，包含单引号的字符串仍作为一个值。

应用可通过 protected 构造器和 validate 增加参数值域约束，覆盖 validate 时保留基础检查。nullable 只放开显式 NULL，非 NULL 仍使用原声明的验证，因此正数等业务约束不会在配置可空后丢失。

输出代理字段需匹配模型的实际列名。HDI fixture 输出 `ID/NAME/AMOUNT`，因此使用 `@Column("ID")` 等显式映射，金额保持 BigDecimal。输出类型无需实现额外的增强查询接口。

## 参数契约

| 声明与调用 | 行为 |
| --- | --- |
| `required(name, type)` | 应用必须显式赋值；缺参在构造查询时拒绝 |
| `optional(name, type)` | 允许省略；不生成该 PLACEHOLDER，数据库负责模型默认行为 |
| `input.nullable()` | 创建允许显式 SQL NULL 的新声明；应先配置，再放入来源定义 |
| `input.bind(value)` | 编译期保持 T，运行时再次校验 NULL 与值类型 |
| 同名但不同的声明对象 | 拒绝；绑定必须引用来源定义中实际声明的输入 |
| 重复声明/重复赋值/未知参数 | 构造查询时拒绝，不静默覆盖 |

`required` 是应用声明，不会查询数据库目录来猜测模型是否存在默认值。把一个数据库必填参数错误地声明成 optional，省略后仍可能由 HANA 报错。显式 NULL 与“省略后取默认值”有不同语义，不能互换。

绑定顺序固定为声明顺序，与调用方提供 Binding 的顺序无关。自定义 Java 值需要该 client 的 JDBC handler；来源参数不自动继承某个输出实体列的 converter。值只保存引用，不深复制业务对象，构造后不要再修改可变值。

子类还可通过 protected `toJdbcValue` 将非 NULL 业务值编码为一个已有 handler 支持的 JDBC 值。编码在构造来源查询时执行一次，clone 复用已编码参数；nullable 保留原编码规则，显式 NULL 不调用编码器。

HANA 2.00.088 的真实 HDI fixture 已分别验证：省略 P_NAME 使用模型的 `other` 默认值，显式 JDBC NULL 返回空结果，带引号/中文的输入精确匹配。默认值使用 SAP 固定示例中的 `defaultRange lowValue`；结果是该模型的行为，不把所有模型的 NULL 过滤规则统一成客户端判断。

## 组合与生命周期

每次 `view.query(...)` 都向 proxyFactory 请求新的未绑定 Proxy。来源定义可作为应用配置复用，同一个视图的两次查询可安全放到 JOIN 两侧；返回已经绑定的 Proxy 会提前拒绝。终端调用和后续分支仍遵循 EasyQuery 3.2.17 的[查询 clone 契约](dsl-projections.md)。

输入仍属于匿名来源，外层 where、groupBy、排序、Draft/DTO、CTE、count/page 使用原查询构造链。来源参数先绑定，外层表达式参数按原有顺序追加。定义本身不缓存 Connection、ResultSet 或运行中的查询对象。

实库测试还验证了原 client 的同一事务：写入未提交行后通过计算视图读取可见，回滚后该行消失。来源类不自行提交、切换连接或创建另一个事务上下文。

实际 fixture 沿用[HDI 部署测试](hana-hdi.md)，先证明对象类型是 CALC，再验证正式查询入口。不会用普通 SQL view 或表函数替代计算视图验证。当前入口覆盖标量与下述多值模型输入；选定表函数仍是 H11 后续交付项。

## 模型多值输入

```java
var names = HanaInput.requiredTextList("P_NAMES");
var ids = HanaInput.requiredNumericList("P_IDS", Integer.class);

var view = HanaCalculationView.of("REPORT_SCHEMA", "SALES_BY_NAMES",
        SaleViewProxy::createTable, names);
var rows = view.query(client, names.bind(List.of("O'Reilly, 中文", "A\"B")));
```

模型必须声明多值输入。文本列表编码为一个 JDBC 字符串，例如 `'O''Reilly, 中文','A"B'`，元素内的逗号保留在该元素中；不能当作普通 CSV。数值列表同样是一个绑定值，如 `4,1`，不逐项加文本引号。SQL 中始终只有一个 `PLACEHOLDER => ?`。

数值元素限定 Byte、Short、Integer、Long、BigInteger、BigDecimal，通过解析后输出规范十进制；不接受浮点/非有限值或任意 Number 子类。数据库模型仍决定实际精度、范围和类型转换。已有实库模型验证 INTEGER 列表，离线检查覆盖 BigDecimal 的规范编码；不同 SQL 数值模型的范围不能仅由 Java 类型推定。

`optionalTextList` / `optionalNumericList` 允许省略参数，是否有默认值由模型决定。空列表、NULL 元素和整个 NULL 列表均拒绝；列表声明不开放 nullable。HANA 2.00.088 的 IN 多值模型会把 JDBC NULL 展开成无效的 `IN ()`。`List.of("")` 则明确表示一个空字符串元素，真实 fixture 已验证它只命中空字符串记录。重复元素沿用数据库 IN 的集合语义，不重复输出行。

多值 XML 的 selection/SQL 过滤形态参考固定 [SAP Cloud 教学样例](https://github.com/SAP-samples/hana-cloud-learning/blob/1f536fc4c256cfefedc8acde6efd62892abf8f8b/calculationViewTraining/inputParameters/db/src/1_solution/calcviews/CV_PRODUCT_DATA_MVIP.hdbcalculationview)，已在本项目 HANA 2.0 HDI 实际部署和查询。模型使用 `multiLine="true"` 与 `IN ($$P_NAMES$$)`，占位符外不包单引号。绑定层协议参见 [SAP 多值赋值说明](https://help.sap.com/docs/SAP_HANA_PLATFORM/88fe5f56472e40cca6ef3c3dcab4855b/a3dcbde19bdc4c7380fb6aabb48c4589.html)；官方直接 SQL 示例的外层字符串引号不能再原样加给 JDBC。

参数名语法与模型默认值依据 [SAP Column View Parameter Binding](https://help.sap.com/docs/SAP_HANA_PLATFORM/de2486ee947e43e684d39702027f8a94/f1c17eb3a5b04f8b82d5908218e3fa68.html) 和 [CS_VIEW_PARAMETERS](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/3abb2714091d495b9eb277ce6f9daa07.html)。本包不自动反查目录推断 API 类型；支持范围以版本固定的实库测试为准。
