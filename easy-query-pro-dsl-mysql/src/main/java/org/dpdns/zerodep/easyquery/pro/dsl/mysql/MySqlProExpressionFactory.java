package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.sql.expression.EntityQuerySQLExpression;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.mysql.expression.MySQLExpressionFactory;
import com.easy.query.mysql.expression.MySQLQuerySQLExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProQuerySQLContext;

/**
 * 只扩展 SELECT 渲染上下文，DML、表、CTE、UNION 和 clone 工厂仍继承上游。
 * 应用定制工厂可继承本类，在 MySqlExtensions.configureQueryScope 之后注册，保留根查询标记。
 */
public class MySqlProExpressionFactory extends MySQLExpressionFactory {
    @Override
    public EntityQuerySQLExpression createEasyQuerySQLExpression(EntitySQLExpressionMetadata metadata) {
        return new QueryExpression(metadata);
    }

    private static final class QueryExpression extends MySQLQuerySQLExpression {
        private QueryExpression(EntitySQLExpressionMetadata metadata) {
            super(metadata);
        }

        @Override
        public String toSQL(ToSQLContext context) {
            return super.toSQL(ProQuerySQLContext.withQueryContext(context, entitySQLExpressionMetadata.getExpressionContext()));
        }
    }
}
