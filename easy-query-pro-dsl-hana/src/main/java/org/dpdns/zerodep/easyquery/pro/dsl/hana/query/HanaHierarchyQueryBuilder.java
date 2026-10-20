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
import java.util.List;

/**
 * 具有真实关系语义的匿名来源：内层不暴露可下推的投影，展示谓词不能变成构树前过滤。
 * 来源子查询始终由上游在同一个 ToSQLContext 渲染，参数 handler、CTE 和绑定顺序不经字符串还原。
 */
final class HanaHierarchyQueryBuilder extends QueryExpressionBuilder implements SQLAnonymousEntityQueryExpressionBuilder {
    private final ClientQueryable<?> source;
    private final HanaHierarchySpec<?> spec;
    private final SQLNativeSegment sourceSegment;
    private final List<SQLNativeSegment> roots;

    HanaHierarchyQueryBuilder(ClientQueryable<?> source, HanaHierarchySpec<?> spec, ExpressionContext context, Class<?> resultClass) {
        super(context, resultClass);
        this.source = source;
        this.spec = spec;
        var nativeContext = new SQLNativeExpressionContextImpl(context, runtimeContext);
        // 在最外层 ToSQLContext 创建前提取 CTE/tableContext，不能等到 toSQL 时才追加依赖。
        nativeContext.expression(source);
        sourceSegment = runtimeContext.getSQLSegmentFactory().createSQLNativeSegment(context, "{0}", nativeContext);
        roots = new ArrayList<>();
        if (spec.roots() != null) {
            for (var root : spec.roots()) {
                var valueContext = new SQLNativeExpressionContextImpl(context, runtimeContext);
                // 比较值沿原列的 SQL converter、JDBC handler 与业务 converter 路径绑定。
                valueContext.sql(EasyColumnSegmentUtil.createColumnCompareValue2Segment(spec.node().getTable(),
                        spec.node().getTable().getEntityMetadata().getColumnNotNull(spec.node().getValue()), context, root));
                roots.add(runtimeContext.getSQLSegmentFactory().createSQLNativeSegment(context, "{0}", valueContext));
            }
        }
        if (context.isSharding())
            throw new UnsupportedOperationException("HANA hierarchies require one database source without shard merging");
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public EntityQuerySQLExpression toExpression() {
        return new HierarchyExpression(new EntitySQLExpressionMetadata(expressionContext, runtimeContext), sourceSegment, roots, spec);
    }

    @Override
    public EntityQueryExpressionBuilder cloneEntityExpressionBuilder() {
        return new HanaHierarchyQueryBuilder(source.cloneQueryable(), spec, expressionContext.cloneExpressionContext(), queryClass);
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
        private final HanaHierarchySpec<?> spec;

        private HierarchyExpression(EntitySQLExpressionMetadata metadata, SQLNativeSegment source,
                                    List<SQLNativeSegment> roots, HanaHierarchySpec<?> spec) {
            super(metadata, "", List.of());
            this.metadata = metadata;
            this.source = source;
            this.roots = List.copyOf(roots);
            this.spec = spec;
        }

        @Override
        public String toSQL(ToSQLContext context) {
            EasySQLExpressionUtil.expressionInvokeRoot(context);
            if (metadata.getExpressionContext().isSharding() || HanaQuerySQLContext.isSharding(context))
                throw new UnsupportedOperationException("HANA hierarchies cannot be combined with shard merging");
            String node = HanaHierarchy.columnName(spec.node());
            String parent = HanaHierarchy.columnName(spec.parent());
            var sql = new StringBuilder("SELECT * FROM HIERARCHY(SOURCE (SELECT * FROM (SELECT \"_EQH_DATA\".*");
            if (!node.equals("NODE_ID"))
                sql.append(",\"_EQH_DATA\".").append(quote(node)).append(" AS \"NODE_ID\"");
            if (!parent.equals("PARENT_ID"))
                sql.append(",\"_EQH_DATA\".").append(quote(parent)).append(" AS \"PARENT_ID\"");
            sql.append(" FROM ").append(source.toSQL(context)).append(" \"_EQH_DATA\") \"_EQH_SOURCE\")");
            // 额外一层 SELECT * 让 START 与兄弟排序都面对规范输出列名，避免原始别名规则差异。
            if (!roots.isEmpty()) {
                sql.append(" START WHERE \"NODE_ID\" IN (");
                for (int i = 0; i < roots.size(); i++) {
                    if (i > 0) sql.append(',');
                    sql.append(roots.get(i).toSQL(context));
                }
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

        private static String quote(String name) {
            return KEYWORDS.quoteUnquotedName(name);
        }

        @Override
        public EntityQuerySQLExpression cloneSQLExpression() {
            return new HierarchyExpression(metadata, source.cloneSQLColumnSegment(),
                    roots.stream().map(SQLNativeSegment::cloneSQLColumnSegment).toList(), spec);
        }
    }
}
