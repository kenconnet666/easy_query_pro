package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.enums.MultiTableTypeEnum;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.sql.expression.EntityDeleteSQLExpression;
import com.easy.query.core.expression.sql.expression.EntityInsertSQLExpression;
import com.easy.query.core.expression.sql.expression.EntityQuerySQLExpression;
import com.easy.query.core.expression.sql.expression.EntityTableSQLExpression;
import com.easy.query.core.expression.sql.expression.EntityUpdateSQLExpression;
import com.easy.query.core.expression.sql.expression.factory.DefaultEasyExpressionFactory;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;

/** Retains upstream table, subquery, UNION, CTE and clone protocols. */
public class HanaExpressionFactory extends DefaultEasyExpressionFactory {
    @Override
    public EntityTableSQLExpression createEntityTableSQLExpression(TableAvailable table, MultiTableTypeEnum type,
                                                                  QueryRuntimeContext runtime) {
        return new HanaTableSQLExpression(table, type, runtime);
    }

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
