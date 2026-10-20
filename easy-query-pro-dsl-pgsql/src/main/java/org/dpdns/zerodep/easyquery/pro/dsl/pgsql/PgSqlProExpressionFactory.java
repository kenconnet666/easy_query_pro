package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.sql.expression.EntityQuerySQLExpression;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.pgsql.expression.PostgresSQLExpressionFactory;
import com.easy.query.pgsql.expression.PostgresSQLQuerySQLExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProQuerySQLContext;

/**
 * 只为 SELECT 传播完整查询的作用域，其他方言工厂和执行过程沿用上游。
 * 应用后置工厂可继承本类，避免在 CTE/clone 内丢失最外层分片标记。
 */
public class PgSqlProExpressionFactory extends PostgresSQLExpressionFactory {
    @Override
    public EntityQuerySQLExpression createEasyQuerySQLExpression(EntitySQLExpressionMetadata metadata) {
        return new QueryExpression(metadata);
    }

    private static final class QueryExpression extends PostgresSQLQuerySQLExpression {
        private QueryExpression(EntitySQLExpressionMetadata metadata) {
            super(metadata);
        }

        @Override
        public String toSQL(ToSQLContext context) {
            return super.toSQL(ProQuerySQLContext.withQueryContext(context, entitySQLExpressionMetadata.getExpressionContext()));
        }
    }
}
