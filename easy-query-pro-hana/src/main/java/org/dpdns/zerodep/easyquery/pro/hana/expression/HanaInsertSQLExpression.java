package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.sql.expression.EntityTableSQLExpression;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.core.expression.sql.expression.impl.InsertSQLExpressionImpl;

final class HanaInsertSQLExpression extends InsertSQLExpressionImpl {
    HanaInsertSQLExpression(EntitySQLExpressionMetadata metadata, EntityTableSQLExpression table) {
        super(metadata, table);
    }

    @Override
    public String toSQL(ToSQLContext context) {
        if (duplicateKeys != null || duplicateKeyUpdateColumns != null) {
            throw new UnsupportedOperationException("HANA conflict writes require an explicit MERGE translation");
        }
        if (columns.isEmpty()) {
            throw new UnsupportedOperationException("HANA inserts require at least one writable column");
        }
        return super.toSQL(context);
    }
}
