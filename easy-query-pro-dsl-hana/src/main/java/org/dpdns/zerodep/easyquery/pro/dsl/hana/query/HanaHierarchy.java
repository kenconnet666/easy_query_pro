package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.api.proxy.entity.select.impl.EasyEntityQueryable;
import com.easy.query.core.basic.api.select.ClientQueryable;
import com.easy.query.core.enums.ContextTypeEnum;
import com.easy.query.core.expression.lambda.SQLFuncExpression1;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.SQLColumn;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyClassUtil;
import com.easy.query.core.util.EasySQLExpressionUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.util.Objects;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;
import com.easy.query.core.expression.sql.builder.EntityQueryExpressionBuilder;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.expression.sql.builder.ExpressionContext;

/**
 * 由原 EntityQueryable 生成 HANA 邻接树/森林，保留原查询的绑定和数据库执行链。
 *
 * <p>定义保存来源快照；后续修改原查询不会改变已定义的树。结果使用显式 DTO Proxy，
 * 原业务列按同名 SQL 列映射，额外元数据用 HIERARCHY_* 列名。结果 DTO 应使用可空属性：
 * HANA 会移除来源 NOT NULL 保证，孤儿循环还可能产生业务属性为 NULL 的合成行。
 *
 * <p>这里不把任意递归 CTE 翻译成树，也不自动把原实体导航加载到内存。输入计算值应先完成投影，
 * 本入口的节点、父节点及兄弟排序只引用来源当前暴露的映射列。
 * 来源必须实际选出这些列；实体类型中存在属性并不能代替投影，原生 SQL 的输出仍由数据库核查。
 *
 * @param <K> 节点键类型，保留根集合的编译约束
 */
public final class HanaHierarchy<K> {
    private static final Set<String> GENERATED_COLUMNS = ImmutableCollections.setOf("HIERARCHY_RANK", "HIERARCHY_TREE_SIZE",
            "HIERARCHY_PARENT_RANK", "HIERARCHY_ROOT_RANK", "HIERARCHY_LEVEL", "HIERARCHY_IS_CYCLE", "HIERARCHY_IS_ORPHAN");
    private final ClientQueryable<?> source;
    private final HanaHierarchySpec<K> spec;
    private final Navigation<K> navigation;

    private HanaHierarchy(ClientQueryable<?> source, HanaHierarchySpec<K> spec, Navigation<K> navigation) {
        this.source = source;
        this.spec = spec;
        this.navigation = navigation;
    }

    public static <P extends ProxyEntity<P, T>, T, K> HanaHierarchy<K> from(
            EntityQueryable<P, T> source, SQLFuncExpression1<P, HanaHierarchySpec<K>> specification) {
        Objects.requireNonNull(source, "source");
        EntityQueryExpressionBuilder builder = source.getClientQueryable().getSQLEntityExpressionBuilder();
        QueryRuntimeContext runtime = builder.getRuntimeContext();
        SqlDialect dialect;
        try {
            dialect = runtime.getService(SqlDialect.class);
        } catch (IllegalArgumentException absent) {
            throw new IllegalStateException("Install HanaExtensions before querying a hierarchy", absent);
        }
        if (dialect != SqlDialect.HANA) throw new IllegalArgumentException("HANA hierarchies require a HANA client");
        if (builder.getExpressionContext().isSharding())
            throw new UnsupportedOperationException("HANA hierarchies require one database source without shard merging");
        HanaHierarchySpec<K> spec = Objects.requireNonNull(Objects.requireNonNull(specification, "specification").apply(source.get1Proxy()), "specification result");
        TableAvailable table = builder.getFromTable().getEntityTable();
        requireColumn(spec.node(), table);
        requireColumn(spec.parent(), table);
        spec.siblings().forEach(sibling -> requireColumn(sibling.column(), table));
        // APT 对业务 record 可能暴露 Object.class 载体；实际值类型必须取实体映射，不能因此放过 raw 调用。
        Class<?> keyType = keyType(spec.node());
        if (keyType != keyType(spec.parent()))
            throw new IllegalArgumentException("Hierarchy node and parent keys must have the same Java type");
        if (spec.roots() != null && spec.roots().stream().anyMatch(root -> !keyType.isInstance(root)))
            throw new IllegalArgumentException("Hierarchy roots require " + keyType.getName());
        String nodeName = columnName(spec.node());
        String parentName = columnName(spec.parent());
        for (ColumnMetadata column : table.getEntityMetadata().getProperty2ColumnMap().values()) {
            String name = column.getName();
            if (GENERATED_COLUMNS.contains(name)
                    || name.equals("NODE_ID") && !name.equals(nodeName)
                    || name.equals("PARENT_ID") && !name.equals(parentName))
                throw new IllegalArgumentException("Hierarchy source column conflicts with generated output: " + name);
        }
        // 只复制 ClientQueryable，避免 EntityQueryable.clone 重绑定调用方的可变 Proxy。
        ClientQueryable<T> snapshot = EasySQLExpressionUtil.cloneAndSelectAllQueryable(source.getClientQueryable());
        return new HanaHierarchy<>(snapshot, spec, null);
    }

    /**
     * 按业务节点键选择后代导航起点，默认包含起点自身。重叠子树按 START_RANK 分别保留结果，
     * 不能把它们按业务键去重，也不能再次相加当作全树总额。
     * 起点采用 IN 集合语义：重复输入键不增加 START 行，多父节点产生的不同 rank 仍分别保留。
     */
    public HanaHierarchy<K> descendantsOf(Collection<? extends K> starts) {
        return navigate(Direction.DESCENDANTS, starts);
    }

    /**
     * 默认包含自身；HIERARCHY_DISTANCE 为祖先层数减起点层数，因此始终小于等于 0。
     * 起点仍使用 IN 集合语义，不因输入键重复而重复创建 START 行。
     */
    public HanaHierarchy<K> ancestorsOf(Collection<? extends K> starts) {
        return navigate(Direction.ANCESTORS, starts);
    }

    /**
     * 限定原生有符号距离的闭区间。0 表示自身，祖先使用负数；反向区间保持数据库的空结果语义。
     * 此处只是导航结果预筛选，不会重新构树或改变 TREE_SIZE；与 spec.depth 的构树剪枝不同。
     */
    public HanaHierarchy<K> distanceBetween(int minimum, int maximum) {
        if (navigation == null)
            throw new IllegalStateException("Select descendants or ancestors before setting a distance range");
        return new HanaHierarchy<>(source, spec, new Navigation<>(navigation.direction(), navigation.starts(), minimum, maximum));
    }

    private HanaHierarchy<K> navigate(Direction direction, Collection<? extends K> starts) {
        if (navigation != null)
            throw new IllegalStateException("Start another navigation from the original hierarchy definition");
        Objects.requireNonNull(starts, "starts");
        Class<?> type = keyType(spec.node());
        if (starts.isEmpty() || starts.stream().anyMatch(value -> !type.isInstance(value)))
            throw new IllegalArgumentException("Hierarchy navigation requires nonempty, non-null " + type.getName() + " start keys");
        for (ColumnMetadata column : spec.node().getTable().getEntityMetadata().getProperty2ColumnMap().values()) {
            if (column.getName().equals("START_RANK") || column.getName().equals("HIERARCHY_DISTANCE"))
                throw new IllegalArgumentException("Hierarchy source column conflicts with navigation output: " + column.getName());
        }
        return new HanaHierarchy<>(source, spec, new Navigation<>(direction, ImmutableCollections.listCopyOf(starts), null, null));
    }

    /**
     * 创建独立结果查询。输出 DTO 使用自己的 handler/converter，不继承来源同名属性的临时读取器。
     * where/order/group/CTE/page 在层级计算之后执行；DEPTH 则属于前面的构树阶段。
     */
    public <P extends ProxyEntity<P, T>, T> EntityQueryable<P, T> query(Supplier<P> proxyFactory) {
        P proxy = Objects.requireNonNull(Objects.requireNonNull(proxyFactory, "proxyFactory").get(), "proxyFactory result");
        if (proxy.getTableOrNull() != null)
            throw new IllegalArgumentException("The hierarchy result factory must return a fresh unbound proxy");
        QueryRuntimeContext runtime = source.getSQLEntityExpressionBuilder().getRuntimeContext();
        // source 的 resultPropTypes/reader map 不能污染新的 DTO 形状；extract 仅提升来源/CTE 等查询依赖。
        ExpressionContext context = runtime.getExpressionBuilderFactory().createExpressionContext(runtime, ContextTypeEnum.QUERY);
        HanaHierarchyQueryBuilder builder = new HanaHierarchyQueryBuilder(source.cloneQueryable(), spec, navigation, context, proxy.getEntityClass());
        return new EasyEntityQueryable<>(proxy, runtime.getSQLClientApiFactory().createQueryable(proxy.getEntityClass(), builder));
    }

    static String columnName(SQLColumn<?, ?> column) {
        return column.getTable().getColumnName(column.getValue());
    }

    private static Class<?> keyType(SQLColumn<?, ?> column) {
        return EasyClassUtil.getObjectTypeWhenPrimitive(column.getTable().getEntityMetadata()
                .getColumnNotNull(column.getValue()).getPropertyType());
    }

    private static void requireColumn(SQLColumn<?, ?> column, TableAvailable source) {
        if (column instanceof DSLSQLFunctionAvailable || column.getTable() != source || column.getValue() == null)
            throw new IllegalArgumentException("Hierarchy keys and sibling order must reference mapped columns of the current source");
        source.getEntityMetadata().getColumnNotNull(column.getValue());
    }

    enum Direction {DESCENDANTS, ANCESTORS}

    static final class Navigation<K> {
        private final Direction direction;
        private final List<K> starts;
        private final Integer minimumDistance;
        private final Integer maximumDistance;

        Navigation(Direction direction, List<K> starts, Integer minimumDistance, Integer maximumDistance) {
            this.direction = direction;
            this.starts = starts;
            this.minimumDistance = minimumDistance;
            this.maximumDistance = maximumDistance;
        }

        public Direction direction() {
            return direction;
        }

        public List<K> starts() {
            return starts;
        }

        public Integer minimumDistance() {
            return minimumDistance;
        }

        public Integer maximumDistance() {
            return maximumDistance;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Navigation)) return false;
            Navigation<?> other = (Navigation<?>) o;
            return Objects.equals(direction, other.direction)
                    && Objects.equals(starts, other.starts)
                    && Objects.equals(minimumDistance, other.minimumDistance)
                    && Objects.equals(maximumDistance, other.maximumDistance);
        }

        @Override
        public int hashCode() {
            return Objects.hash(direction, starts, minimumDistance, maximumDistance);
        }

        @Override
        public String toString() {
            return "Navigation[direction=" + direction + ", starts=" + starts + ", minimumDistance=" + minimumDistance + ", maximumDistance=" + maximumDistance + "]";
        }
    }
}
