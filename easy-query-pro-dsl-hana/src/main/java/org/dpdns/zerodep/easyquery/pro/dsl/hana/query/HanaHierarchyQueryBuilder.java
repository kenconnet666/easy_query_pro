package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import com.easy.query.core.basic.api.select.ClientQueryable;
import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.segment.SQLNativeSegment;
import com.easy.query.core.expression.segment.scec.context.SQLNativeExpressionContextImpl;
import com.easy.query.core.expression.sql.builder.EntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import com.easy.query.core.expression.sql.builder.SQLAnonymousEntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.impl.QueryExpressionBuilder;
import com.easy.query.core.expression.sql.expression.EntityQuerySQLExpression;
import com.easy.query.core.expression.sql.expression.impl.AnonymousEntityQuerySQLExpressionImpl;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.core.expression.visitor.TableVisitor;
import com.easy.query.core.util.EasySQLExpressionUtil;
import com.easy.query.core.util.EasyColumnSegmentUtil;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaQuerySQLContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 具有真实关系语义的匿名来源：内层不暴露可下推的投影，展示谓词不能变成构树前过滤。
 * 来源子查询始终由上游在同一个 ToSQLContext 渲染，参数 handler、CTE 和绑定顺序不经字符串还原。
 */
final class HanaHierarchyQueryBuilder extends QueryExpressionBuilder implements SQLAnonymousEntityQueryExpressionBuilder {
    private final ClientQueryable<?> source;
    private final HanaHierarchySpec<?> spec;
    private final HanaHierarchy.Navigation<?> navigation;
    private final SQLNativeSegment sourceSegment;
    private final List<SQLNativeSegment> roots;
    private final List<SQLNativeSegment> starts;
    private final SQLNativeSegment minimumDistance;
    private final SQLNativeSegment maximumDistance;

    HanaHierarchyQueryBuilder(ClientQueryable<?> source, HanaHierarchySpec<?> spec, HanaHierarchy.Navigation<?> navigation,
                              ExpressionContext context, Class<?> resultClass) {
        super(context, resultClass);
        this.source = source;
        this.spec = spec;
        this.navigation = navigation;
        var nativeContext = new SQLNativeExpressionContextImpl(context, runtimeContext);
        // 在最外层 ToSQLContext 创建前提取 CTE/tableContext，不能等到 toSQL 时才追加依赖。
        nativeContext.expression(source);
        sourceSegment = runtimeContext.getSQLSegmentFactory().createSQLNativeSegment(context, "{0}", nativeContext);
        roots = bindKeys(spec.roots());
        starts = bindKeys(navigation == null ? null : navigation.starts());
        minimumDistance = bindDistance(navigation == null ? null : navigation.minimumDistance());
        maximumDistance = bindDistance(navigation == null ? null : navigation.maximumDistance());
        if (context.isSharding())
            throw new UnsupportedOperationException("HANA hierarchies require one database source without shard merging");
    }

    private List<SQLNativeSegment> bindKeys(Collection<?> keys) {
        if (keys == null) return List.of();
        var segments = new ArrayList<SQLNativeSegment>();
        for (var key : keys) {
            var valueContext = new SQLNativeExpressionContextImpl(expressionContext, runtimeContext);
            // 比较值沿原列的 SQL converter、JDBC handler 与业务 converter 路径绑定。
            valueContext.sql(EasyColumnSegmentUtil.createColumnCompareValue2Segment(spec.node().getTable(),
                    spec.node().getTable().getEntityMetadata().getColumnNotNull(spec.node().getValue()), expressionContext, key));
            segments.add(runtimeContext.getSQLSegmentFactory().createSQLNativeSegment(expressionContext, "{0}", valueContext));
        }
        return List.copyOf(segments);
    }

    private SQLNativeSegment bindDistance(Integer value) {
        if (value == null) return null;
        var valueContext = new SQLNativeExpressionContextImpl(expressionContext, runtimeContext);
        valueContext.value(value);
        return runtimeContext.getSQLSegmentFactory().createSQLNativeSegment(expressionContext, "{0}", valueContext);
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public EntityQuerySQLExpression toExpression() {
        return new HierarchyExpression(new EntitySQLExpressionMetadata(expressionContext, runtimeContext), sourceSegment,
                roots, starts, minimumDistance, maximumDistance, spec, navigation);
    }

    @Override
    public EntityQueryExpressionBuilder cloneEntityExpressionBuilder() {
        return new HanaHierarchyQueryBuilder(source.cloneQueryable(), spec, navigation, expressionContext.cloneExpressionContext(), queryClass);
    }

    @Override
    public void accept(TableVisitor visitor) {
        source.getSQLEntityExpressionBuilder().accept(visitor);
    }

    private static final class HierarchyExpression extends AnonymousEntityQuerySQLExpressionImpl {
        private static final HanaSQLKeyword KEYWORDS = new HanaSQLKeyword();
        private final EntitySQLExpressionMetadata metadata;
        private final SQLNativeSegment source;
        private final List<SQLNativeSegment> roots;
        private final List<SQLNativeSegment> starts;
        private final SQLNativeSegment minimumDistance;
        private final SQLNativeSegment maximumDistance;
        private final HanaHierarchySpec<?> spec;
        private final HanaHierarchy.Navigation<?> navigation;

        private HierarchyExpression(EntitySQLExpressionMetadata metadata, SQLNativeSegment source,
                                    List<SQLNativeSegment> roots, List<SQLNativeSegment> starts,
                                    SQLNativeSegment minimumDistance, SQLNativeSegment maximumDistance,
                                    HanaHierarchySpec<?> spec, HanaHierarchy.Navigation<?> navigation) {
            super(metadata, "", List.of());
            this.metadata = metadata;
            this.source = source;
            this.roots = List.copyOf(roots);
            this.starts = List.copyOf(starts);
            this.minimumDistance = minimumDistance;
            this.maximumDistance = maximumDistance;
            this.spec = spec;
            this.navigation = navigation;
        }

        @Override
        public String toSQL(ToSQLContext context) {
            EasySQLExpressionUtil.expressionInvokeRoot(context);
            if (metadata.getExpressionContext().isSharding() || HanaQuerySQLContext.isSharding(context))
                throw new UnsupportedOperationException("HANA hierarchies cannot be combined with shard merging");
            String generator = generator(context);
            if (navigation == null) return "SELECT * FROM " + generator;
            // 导航 SOURCE 接受生成函数，不能把 SELECT 子查询直接放进去；这里组合受控函数节点。
            var sql = new StringBuilder("SELECT * FROM HIERARCHY_").append(navigation.direction().name())
                    .append("(SOURCE ").append(generator).append(" START WHERE \"NODE_ID\" IN (");
            appendKeys(sql, starts, context);
            sql.append(')');
            if (minimumDistance != null) {
                sql.append(" DISTANCE FROM ").append(minimumDistance.toSQL(context))
                        .append(" TO ").append(maximumDistance.toSQL(context));
            }
            return sql.append(')').toString();
        }

        private String generator(ToSQLContext context) {
            String node = HanaHierarchy.columnName(spec.node());
            String parent = HanaHierarchy.columnName(spec.parent());
            var sql = new StringBuilder("HIERARCHY(SOURCE (SELECT * FROM (SELECT \"_EQH_DATA\".*");
            if (!node.equals("NODE_ID"))
                sql.append(",\"_EQH_DATA\".").append(quote(node)).append(" AS \"NODE_ID\"");
            if (!parent.equals("PARENT_ID"))
                sql.append(",\"_EQH_DATA\".").append(quote(parent)).append(" AS \"PARENT_ID\"");
            sql.append(" FROM ").append(source.toSQL(context)).append(" \"_EQH_DATA\") \"_EQH_SOURCE\")");
            // 额外一层 SELECT * 让 START 与兄弟排序都面对规范输出列名，避免原始别名规则差异。
            if (!roots.isEmpty()) {
                sql.append(" START WHERE \"NODE_ID\" IN (");
                appendKeys(sql, roots, context);
                sql.append(')');
            }
            sql.append(" SIBLING ORDER BY ");
            for (var sibling : spec.siblings()) {
                sql.append(quote(HanaHierarchy.columnName(sibling.column())))
                        .append(sibling.ascending() ? " ASC," : " DESC,");
            }
            // 正常树的节点键唯一，补充稳定次序使同序号兄弟在导航和分页时保持确定性。
            sql.append("\"NODE_ID\" ASC");
            if (spec.depth() != null) sql.append(" DEPTH ").append(spec.depth());
            sql.append(spec.multiparent() == HanaHierarchySpec.Multiparent.ERROR ? " MULTIPARENT ERROR" : " MULTIPARENT");
            if (spec.depth() == null) sql.append(" ORPHAN ").append(spec.orphans().name());
            sql.append(" CYCLE ").append(spec.cycles().name()).append(')');
            return sql.toString();
        }

        private static void appendKeys(StringBuilder sql, List<SQLNativeSegment> keys, ToSQLContext context) {
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append(keys.get(i).toSQL(context));
            }
        }

        private static String quote(String name) {
            return KEYWORDS.quoteUnquotedName(name);
        }

        @Override
        public EntityQuerySQLExpression cloneSQLExpression() {
            return new HierarchyExpression(metadata, source.cloneSQLColumnSegment(),
                    roots.stream().map(SQLNativeSegment::cloneSQLColumnSegment).toList(),
                    starts.stream().map(SQLNativeSegment::cloneSQLColumnSegment).toList(),
                    minimumDistance == null ? null : minimumDistance.cloneSQLColumnSegment(),
                    maximumDistance == null ? null : maximumDistance.cloneSQLColumnSegment(), spec, navigation);
        }
    }
}
