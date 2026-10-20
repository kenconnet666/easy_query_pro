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
import java.util.Set;
import java.util.function.Supplier;

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
    private static final Set<String> GENERATED_COLUMNS = Set.of("HIERARCHY_RANK", "HIERARCHY_TREE_SIZE",
            "HIERARCHY_PARENT_RANK", "HIERARCHY_ROOT_RANK", "HIERARCHY_LEVEL", "HIERARCHY_IS_CYCLE", "HIERARCHY_IS_ORPHAN");
    private final ClientQueryable<?> source;
    private final HanaHierarchySpec<K> spec;

    private HanaHierarchy(ClientQueryable<?> source, HanaHierarchySpec<K> spec) {
        this.source = source;
        this.spec = spec;
    }

    public static <P extends ProxyEntity<P, T>, T, K> HanaHierarchy<K> from(
            EntityQueryable<P, T> source, SQLFuncExpression1<P, HanaHierarchySpec<K>> specification) {
        Objects.requireNonNull(source, "source");
        var builder = source.getClientQueryable().getSQLEntityExpressionBuilder();
        var runtime = builder.getRuntimeContext();
        SqlDialect dialect;
        try {
            dialect = runtime.getService(SqlDialect.class);
        } catch (IllegalArgumentException absent) {
            throw new IllegalStateException("Install HanaExtensions before querying a hierarchy", absent);
        }
        if (dialect != SqlDialect.HANA) throw new IllegalArgumentException("HANA hierarchies require a HANA client");
        if (builder.getExpressionContext().isSharding())
            throw new UnsupportedOperationException("HANA hierarchies require one database source without shard merging");
        var spec = Objects.requireNonNull(Objects.requireNonNull(specification, "specification").apply(source.get1Proxy()), "specification result");
        var table = builder.getFromTable().getEntityTable();
        requireColumn(spec.node(), table);
        requireColumn(spec.parent(), table);
        spec.siblings().forEach(sibling -> requireColumn(sibling.column(), table));
        // APT 对业务 record 可能暴露 Object.class 载体；实际值类型必须取实体映射，不能因此放过 raw 调用。
        var keyType = keyType(spec.node());
        if (keyType != keyType(spec.parent()))
            throw new IllegalArgumentException("Hierarchy node and parent keys must have the same Java type");
        if (spec.roots() != null && spec.roots().stream().anyMatch(root -> !keyType.isInstance(root)))
            throw new IllegalArgumentException("Hierarchy roots require " + keyType.getName());
        String nodeName = columnName(spec.node());
        String parentName = columnName(spec.parent());
        for (var column : table.getEntityMetadata().getProperty2ColumnMap().values()) {
            String name = column.getName();
            if (GENERATED_COLUMNS.contains(name)
                    || name.equals("NODE_ID") && !name.equals(nodeName)
                    || name.equals("PARENT_ID") && !name.equals(parentName))
                throw new IllegalArgumentException("Hierarchy source column conflicts with generated output: " + name);
        }
        // 只复制 ClientQueryable，避免 EntityQueryable.clone 重绑定调用方的可变 Proxy。
        var snapshot = EasySQLExpressionUtil.cloneAndSelectAllQueryable(source.getClientQueryable());
        return new HanaHierarchy<>(snapshot, spec);
    }

    /**
     * 创建独立结果查询。输出 DTO 使用自己的 handler/converter，不继承来源同名属性的临时读取器。
     * where/order/group/CTE/page 在层级计算之后执行；DEPTH 则属于前面的构树阶段。
     */
    public <P extends ProxyEntity<P, T>, T> EntityQueryable<P, T> query(Supplier<P> proxyFactory) {
        var proxy = Objects.requireNonNull(Objects.requireNonNull(proxyFactory, "proxyFactory").get(), "proxyFactory result");
        if (proxy.getTableOrNull() != null)
            throw new IllegalArgumentException("The hierarchy result factory must return a fresh unbound proxy");
        var runtime = source.getSQLEntityExpressionBuilder().getRuntimeContext();
        // source 的 resultPropTypes/reader map 不能污染新的 DTO 形状；extract 仅提升来源/CTE 等查询依赖。
        var context = runtime.getExpressionBuilderFactory().createExpressionContext(runtime, ContextTypeEnum.QUERY);
        var builder = new HanaHierarchyQueryBuilder(source.cloneQueryable(), spec, context, proxy.getEntityClass());
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
}
