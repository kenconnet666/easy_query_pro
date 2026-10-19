package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.sql.builder.impl.AnonymousTreeCTERECURSIVEQueryExpressionBuilder;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.core.expression.sql.expression.impl.QuerySQLExpressionImpl;

final class HanaQuerySQLExpression extends QuerySQLExpressionImpl {
    HanaQuerySQLExpression(EntitySQLExpressionMetadata metadata) {
        super(metadata);
    }

    @Override
    public String toSQL(ToSQLContext context) {
        var expression = entitySQLExpressionMetadata.getExpressionContext();
        if (expression.hasDeclareExpressions() && expression.getDeclareExpressions().stream()
                .anyMatch(AnonymousTreeCTERECURSIVEQueryExpressionBuilder.class::isInstance)) {
            throw new UnsupportedOperationException("HANA 2.0 does not support recursive WITH; use HANA hierarchy queries");
        }
        return super.toSQL(context);
    }
}
