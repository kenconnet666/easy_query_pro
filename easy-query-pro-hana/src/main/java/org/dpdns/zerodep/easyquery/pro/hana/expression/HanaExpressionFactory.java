package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.expression.sql.expression.EntityDeleteSQLExpression;
import com.easy.query.core.expression.sql.expression.EntityInsertSQLExpression;
import com.easy.query.core.expression.sql.expression.EntityQuerySQLExpression;
import com.easy.query.core.expression.sql.expression.EntityTableSQLExpression;
import com.easy.query.core.expression.sql.expression.EntityUpdateSQLExpression;
import com.easy.query.core.expression.sql.expression.factory.DefaultEasyExpressionFactory;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;

/** Retains upstream table, subquery, UNION, CTE and clone protocols. */
public final class HanaExpressionFactory extends DefaultEasyExpressionFactory {
    @Override
    public EntityQuerySQLExpression createEasyQuerySQLExpression(EntitySQLExpressionMetadata metadata) {
        return new HanaQuerySQLExpression(metadata);
    }

    @Override
    public EntityInsertSQLExpression createEasyInsertSQLExpression(EntitySQLExpressionMetadata metadata,
                                                                  EntityTableSQLExpression table) {
        return new HanaInsertSQLExpression(metadata, table);
    }

    @Override
    public EntityUpdateSQLExpression createEasyUpdateSQLExpression(EntitySQLExpressionMetadata metadata,
                                                                  EntityTableSQLExpression table) {
        return new HanaUpdateSQLExpression(metadata, table);
    }

    @Override
    public EntityDeleteSQLExpression createEasyDeleteSQLExpression(EntitySQLExpressionMetadata metadata,
                                                                  EntityTableSQLExpression table) {
        return new HanaDeleteSQLExpression(metadata, table);
    }
}
