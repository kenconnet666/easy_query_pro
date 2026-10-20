package org.dpdns.zerodep.easyquery.pro.dsl.mysql.query;

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
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProQuerySQLContext;

import java.util.List;

/**
 * 同一次 SQL 上下文中的来源边界，完整委托子查询参数并阻止展示谓词改变匹配层。
 */
final class MySqlSearchQueryBuilder extends QueryExpressionBuilder implements SQLAnonymousEntityQueryExpressionBuilder {
    private final ClientQueryable<?> source;
    private final SQLNativeSegment sourceSegment;

    MySqlSearchQueryBuilder(ClientQueryable<?> source, ExpressionContext context, Class<?> resultClass) {
        super(context, resultClass);
        this.source = source;
        var nativeContext = new SQLNativeExpressionContextImpl(context, runtimeContext);
        nativeContext.expression(source);
        sourceSegment = runtimeContext.getSQLSegmentFactory().createSQLNativeSegment(context, "SELECT * FROM {0} `_EQM_SEARCH`", nativeContext);
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
        return new MySqlSearchQueryBuilder(source.cloneQueryable(), expressionContext.cloneExpressionContext(), queryClass);
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
            // 缺少安装时不能把未知当成非分片；后置自定义工厂也必须传播同一根标记。
            if (!(context instanceof ProQuerySQLContext))
                throw new IllegalStateException("Install MySqlExtensions.configureQueryScope and preserve ProQuerySQLContext in custom query factories");
            EasySQLExpressionUtil.expressionInvokeRoot(context);
            if (metadata.getExpressionContext().isSharding() || ProQuerySQLContext.isSharding(context))
                throw new UnsupportedOperationException("MySQL full-text search cannot be combined with shard merging");
            return source.toSQL(context);
        }

        @Override
        public EntityQuerySQLExpression cloneSQLExpression() {
            return new SearchExpression(metadata, source.cloneSQLColumnSegment());
        }
    }
}
