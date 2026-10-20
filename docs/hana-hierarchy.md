# HANA 邻接树与森林

`HanaHierarchy` 将已有 `EntityQueryable` 的节点/父节点关系交给 HANA 原生 `HIERARCHY`，结果继续使用原查询 API。来源过滤、值转换、参数与 CTE 保留在同一数据库执行链中；不会先读取整张表到 Java，也不会把不受支持的递归 WITH 改写成一个近似查询。

```java
var source = query.queryable(Department.class)
        .where(department -> department.tenantId().eq(tenantId));

var hierarchy = HanaHierarchy.from(source, department ->
        HanaHierarchySpec.of(department.id(), department.parentId())
                .siblingsAsc(department.position())
                .roots(List.of(rootDepartmentId)));

var page = hierarchy.query(DepartmentNodeProxy::createTable)
        .where(node -> node.level().le(3))
        .orderBy(node -> node.rank().asc())
        .toPageResult(1, 50);
```

上例中的 `DepartmentNode` 是应用自己的输出 DTO，使用 `@EntityProxy` 生成 Proxy，并以 `@Column` 映射下表的原生元数据；业务列按来源输出名称映射。每次 query 的工厂必须创建新的未绑定 Proxy，不能让同一个已绑定 Proxy 同时代表两侧 JOIN。

| 输出 SQL 列 | 建议 Java 类型 | 含义 |
| --- | --- | --- |
| `HIERARCHY_RANK` | `Long` | 前序遍历序号，标识本次结果中的一个节点 |
| `HIERARCHY_TREE_SIZE` | `Long` | 当前节点及其后代的行数 |
| `HIERARCHY_PARENT_RANK` | `Long` | 父节点遍历序号；根节点为 0 |
| `HIERARCHY_ROOT_RANK` | `Long` | 所属树的根序号 |
| `HIERARCHY_LEVEL` | `Integer` | 生成器中的层数，从 1 开始 |
| `HIERARCHY_IS_CYCLE` | `Short` | 0/1，当前边是否闭合一个循环 |
| `HIERARCHY_IS_ORPHAN` | `Short` | 0/1，是否通过孤儿处理遍历 |
| 原来源列、`NODE_ID`、`PARENT_ID` | 与真实 SQL 输出匹配的可空类型 | 来源业务属性与规范化后的关系键 |

这些 SQL 类型来自 [HANA 2.0 Hierarchy Developer Guide](https://help.sap.com/doc/e4fc5d26a38c4dc28802a3b87cd5eb10/2.0.08/en-US/SAP_HANA_Hierarchy_Developer_Guide_en.pdf#page=40)。HANA 会移除所有来源业务列的 NOT NULL 保证；处理孤儿循环时还可能生成业务属性为 NULL 的行。输出 DTO 应使用包装类型，不将这些 NULL 默认为 0。

## 类型与来源契约

`HanaHierarchySpec<K>` 的节点键、父键、根集合保持同一个 K。构造时检查键和排序列属于当前来源暴露的同一映射表，并拒绝旧 JOIN 子代理、外来来源和函数列。计算键或排序值先投影到一个具名 DTO，再使用其映射列。来源必须实际投影这些列；实体声明中存在属性不代表已选出，原生 SQL 的输出仍由数据库检查。

节点键与父键在 HANA 中还必须具有相同 SQL 类型；Java 类型相同不能证明物理类型相同。根值使用节点列原有的 SQL converter、业务 converter 和 JDBC handler，整个来源的参数也不提取成裸值后重绑。自定义业务键不需要在调用前手动序列化。

构造层级定义时复制来源 ClientQueryable，后续为原查询增加 where 不影响已捕获的树。定义可反复调用 query，每次产生独立的结果代理和查询状态；原查询参数及根元素中的可变业务对象不会被深复制。输出 DTO 使用自己的列读取契约，来源临时 valueConvert 的同名 reader 不会覆盖目标 DTO。

内部把来源规范化成带有 `NODE_ID/PARENT_ID` 的派生关系，再包一层 SELECT。这是因为 HANA 的 START 与兄弟排序对来源别名有不同规则。业务列保留；已存在的规范键必须与选定属性对应，与生成 `HIERARCHY_*` 列冲突的来源提前拒绝。名称分别引用，参数全部保持绑定。

## 构树与展示

| 操作位置 | 影响 |
| --- | --- |
| 传入 from 之前的 source.where | 决定哪些边存在于树中，会改变结构、孤儿与子树大小 |
| spec.roots | 选择遍历起点；不把其余节点先从来源删除 |
| spec.depth | 在构建时截断树，会改变子树大小；根深度为 0 |
| query 后的 where | 筛选已生成结果，保留原 tree_size 和层级属性 |

未指定 roots 时，父键为 SQL NULL 的记录构成根集合；0、空字符串等业务哨兵不会被自动转为 NULL。显式根集合不能为空。来源条件排除所有记录、或根集合在来源中没有匹配时，按 HANA 原生遍历规则产生结果。

兄弟排序只接受映射列，最后补节点键升序作为稳定次序。query 的输出行顺序仍应显式 `orderBy(rank)`；不能把兄弟排序误认为最终结果排序。普通树中的节点键必须唯一；允许多父节点时，应额外提供能够区分边的排序列，并把 rank 用作一次遍历中的标识。

## 异常数据策略

默认 `Multiparent.ERROR`、`Cycles.ERROR`，孤儿为 `Orphans.IGNORE`。重复节点、循环与孤儿错误由 HANA 验证，不把这些异常吞掉或在 Java 中去重。

- `Orphans.IGNORE`：不遍历从所选根不可达的节点。
- `Orphans.ROOT`：将不可达部分的顶层节点作为额外根；因此显式 roots 与该策略并用时，结果可能包含 roots 之外的树。
- `Orphans.ERROR`：存在不可达节点时报错。
- `Cycles.BREAKUP`：输出循环闭合行并停止继续递归。循环会产生重复节点，消费这类结果还需要 `Multiparent.ALLOW`。
- `depth(n)`：n 必须非负，且只能与 `Orphans.IGNORE` 并用；截断与完整孤儿检查不能同时成立。

完整 SQL 规则见 [HANA 2.0 生成器章节](https://help.sap.com/doc/e4fc5d26a38c4dc28802a3b87cd5eb10/2.0.08/en-US/SAP_HANA_Hierarchy_Developer_Guide_en.pdf#page=11)。该入口选择邻接树/森林范围，不自动扩展为任意图、最短路径、时态树或多列复合键算法。

## 查询组合与当前边界

输出是原 `EntityQueryable`，可以继续 DTO/Draft 投影、JOIN、CTE、聚合、count/page 和 clone。SMART 后置过滤不能穿透层级来源成为构树前过滤；来源参数、根参数和外层条件按最终 SQL 顺序进入同一个参数上下文。

层级依赖完整的数据库关系，不支持跨分片生成后由 Java 合并。来源已经分片时提前拒绝；查询 clone、CTE 后再组合分片 JOIN 也必须拒绝。HANA 渲染上下文透传本次外层分片标记，避免内外 ExpressionContext 分别复制后丢失该约束。

生成器、下面的祖先/后代导航与组织事实汇总已经过本地实库验证，完整版本、权限和业务组合仍按 H12/G6 继续验收。测试采用常量关系，没有新增持久表或容器；完整证据见 [执行记录](execution.md)。

## 祖先、后代与距离

```java
var descendants = hierarchy.descendantsOf(List.of(rootDepartmentId, otherDepartmentId))
        .distanceBetween(0, 2)
        .query(DepartmentNavigationProxy::createTable)
        .orderBy(row -> {
            row.startRank().asc();
            row.rank().asc();
        });

var ancestors = hierarchy.ancestorsOf(List.of(leafDepartmentId))
        .distanceBetween(-2, -1)
        .query(DepartmentNavigationProxy::createTable);
```

导航 DTO 除基础属性外，可映射 `@Column("START_RANK") Long startRank` 和 `@Column("HIERARCHY_DISTANCE") Integer distance`。这两列只在导航结果存在，应使用独立 DTO 或继承基础 DTO 后扩展字段。

两种导航默认包含自身，距离 0 为起点；后代距离非负，祖先距离非正。`distanceBetween(min,max)` 是原生有符号闭区间，`(-2,-1)` 表示父级与祖父级，`(0,0)` 仅保留自身。反向区间保持空结果，祖先使用正区间也为空；接口不会擅自取绝对值或调换边界。距离使用 JDBC 参数，并在导航物化之前筛选，原始 `TREE_SIZE` 保持不变。

起点要求非空且不含 NULL，Java 类型与节点键一致。它使用 IN 集合语义：同一个输入键重复两次不会额外增加 START 行；多父节点中的不同 rank 则分别参与。不同起点的子树重叠时，同一业务节点会按起点重复出现在结果中，必须用 `(START_RANK, HIERARCHY_RANK)` 识别一条导航关系。不要按 NODE_ID 自动去重，也不要把重叠子树金额再次相加冒充全树总额。

不存在的起点或空来源得到空结果。接口不提供“省略起点则从所有节点导航”的隐式重载，避免把一次子树查询无意扩大为完整关系闭包。每次导航返回新定义，原 hierarchy 仍可查询全部生成结果；如果需要另一种导航，应从原定义重新调用，不将已经导航过的结果误当作完整树。

实现直接把受控 `HIERARCHY(...)` 函数节点放进导航的 SOURCE。HANA 2.0 的导航 SOURCE 不接收任意普通子查询，因此没有先生成 SELECT 再剥离 SQL 文本。依据为 [HANA 2.0 导航章节](https://help.sap.com/doc/e4fc5d26a38c4dc28802a3b87cd5eb10/2.0.08/en-US/SAP_HANA_Hierarchy_Developer_Guide_en.pdf#page=45)，实际 JDBC 检查包含两种方向、距离绑定、重叠、空集、业务键、CTE/count/page 和克隆。

## 组织事实汇总

事实汇总继续复用原有 GROUP BY、SUM/COUNT、LEFT JOIN 和 DTO/Draft。先按节点键将一对多事实折叠为一行，再与后代导航结果关联，最后按 START_RANK 汇总。这样保留空叶和无事实起点，同时节点自身的预算、面积等度量不会因为多条事实而重复累计。

`HanaHierarchyIT.factPreaggregationPreservesNodeMeasuresAndEmptyBranches` 的实库结果明确区分：

| 起点 | 事实金额 SUM | 节点自身金额 SUM | 节点数 | 事实记录数 |
| --- | --- | --- | --- | --- |
| 1 | 75 | 100 | 4 | 4 |
| 3（与起点 1 重叠） | 40 | 70 | 2 | 1 |
| 5（有一条 NULL 金额事实） | NULL | 110 | 2 | 1 |
| 7（完全没有事实） | NULL | 70 | 1 | 0 |

事实记录数按原始行 COUNT 后求和，对全无事实的分支使用既有 `nullOrDefault(0L)`。金额 SUM 保持 SQL NULL，不把“无金额”自动解释成零；需要不同业务规则时由调用方明确处理。不能用 `COUNT(amount)` 代替事实记录数，也不能用 `SUM(DISTINCT amount)` 掩盖连接导致的重复。未匹配任何节点的事实不会混入汇总。

结果展示筛选在汇总之后执行；实库通过 SMART 下只展示起点 3、CTE 与分页仍保留正确总额。该方案用已有查询能力组成单条数据库报表，不新增报表执行引擎或重复的聚合 API。
