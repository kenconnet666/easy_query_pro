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

当前实现覆盖生成器。祖先/后代导航、距离与组织事实汇总仍按 H12/G5.3/G5.8 继续实施，当前生成器验收不替代这些交付项。测试采用常量关系，没有新增持久表或容器；完整证据见 [执行记录](execution.md)。
