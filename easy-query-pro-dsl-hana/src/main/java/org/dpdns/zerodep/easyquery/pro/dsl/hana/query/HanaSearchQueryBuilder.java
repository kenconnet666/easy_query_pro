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
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaQuerySQLContext;

import java.util.List;

/**
 * 空投影的来源边界阻止 SMART 把展示谓词下推到 CONTAINS/SCORE 所在层，绑定仍由上游子查询处理。
 */
final class HanaSearchQueryBuilder extends QueryExpressionBuilder implements SQLAnonymousEntityQueryExpressionBuilder {
    private final ClientQueryable<?> source;
    private final SQLNativeSegment sourceSegment;

    HanaSearchQueryBuilder(ClientQueryable<?> source, ExpressionContext context, Class<?> resultClass) {
        super(context, resultClass);
        this.source = source;
        var nativeContext = new SQLNativeExpressionContextImpl(context, runtimeContext);
        nativeContext.expression(source);
        sourceSegment = runtimeContext.getSQLSegmentFactory().createSQLNativeSegment(context, "SELECT * FROM {0} \"_EQH_SEARCH\"", nativeContext);
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public EntityQuerySQLExpression toExpression() {
        return new SearchExpression(new EntitySQLExpressionMetadata(expressionContext, runtimeContext), sourceSegment);
    }

    @Override
    public EntityQueryExpressionBuilder cloneEntityExpressionBuilder() {
        return new HanaSearchQueryBuilder(source.cloneQueryable(), expressionContext.cloneExpressionContext(), queryClass);
    }

    @Override
    public void accept(TableVisitor visitor) {
        source.getSQLEntityExpressionBuilder().accept(visitor);
    }

    private static final class SearchExpression extends AnonymousEntityQuerySQLExpressionImpl {
        private final EntitySQLExpressionMetadata metadata;
        private final SQLNativeSegment source;

        private SearchExpression(EntitySQLExpressionMetadata metadata, SQLNativeSegment source) {
            super(metadata, "", List.of());
            this.metadata = metadata;
            this.source = source;
        }

        @Override
        public String toSQL(ToSQLContext context) {
            EasySQLExpressionUtil.expressionInvokeRoot(context);
            if (metadata.getExpressionContext().isSharding() || HanaQuerySQLContext.isSharding(context))
                throw new UnsupportedOperationException("HANA full-text search cannot be combined with shard merging");
            return source.toSQL(context);
        }

        @Override
        public EntityQuerySQLExpression cloneSQLExpression() {
            return new SearchExpression(metadata, source.cloneSQLColumnSegment());
        }
    }
}
