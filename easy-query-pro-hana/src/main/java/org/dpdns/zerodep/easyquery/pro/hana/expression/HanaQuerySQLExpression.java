package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.sql.builder.impl.AnonymousTreeCTERECURSIVEQueryExpressionBuilder;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.core.expression.sql.expression.impl.QuerySQLExpressionImpl;
import com.easy.query.core.expression.sql.builder.ExpressionContext;

final class HanaQuerySQLExpression extends QuerySQLExpressionImpl {
    HanaQuerySQLExpression(EntitySQLExpressionMetadata metadata) {
        super(metadata);
    }

    @Override
    public String toSQL(ToSQLContext context) {
        if (distinct && projects != null && projects.getSQLSegments().stream().anyMatch(HanaSQLSegmentFactory::hasLobResult)) {
            throw HanaSQLSegmentFactory.unsupported("DISTINCT");
        }
        if (group != null && group.getSQLSegments().stream().anyMatch(HanaSQLSegmentFactory::hasLobResult))
            throw HanaSQLSegmentFactory.unsupported("GROUP BY");
        if (order != null && order.getSQLSegments().stream().anyMatch(HanaSQLSegmentFactory::hasLobResult))
            throw HanaSQLSegmentFactory.unsupported("ORDER BY");
        ExpressionContext expression = entitySQLExpressionMetadata.getExpressionContext();
        if (expression.hasDeclareExpressions() && expression.getDeclareExpressions().stream()
                .anyMatch(AnonymousTreeCTERECURSIVEQueryExpressionBuilder.class::isInstance)) {
            throw new UnsupportedOperationException("HANA 2.0 does not support recursive WITH; use HANA hierarchy queries");
        }
        return super.toSQL(HanaQuerySQLContext.withQueryContext(context, expression));
    }
}
