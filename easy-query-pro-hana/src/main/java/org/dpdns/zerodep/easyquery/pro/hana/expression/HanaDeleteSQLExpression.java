package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.sql.expression.EntityTableSQLExpression;
import com.easy.query.core.expression.sql.expression.impl.DeleteSQLExpressionImpl;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;

final class HanaDeleteSQLExpression extends DeleteSQLExpressionImpl {
    HanaDeleteSQLExpression(EntitySQLExpressionMetadata metadata, EntityTableSQLExpression table) {
        super(metadata, table);
    }

    @Override
    public String toSQL(ToSQLContext context) {
        if (tables.size() != 1) {
            throw new UnsupportedOperationException("HANA joined DELETE requires a dedicated translation");
        }
        return super.toSQL(context);
    }
}
