package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.expression.predicate.DefaultSmartPredicateAnonymousExpressionBuilderProvider;
import com.easy.query.core.expression.segment.condition.PredicateSegment;
import com.easy.query.core.expression.sql.builder.AnonymousEntityTableExpressionBuilder;

/**
 * Keeps post-window filters outside the partition while retaining upstream optimization elsewhere.
 */
public class ProSmartPredicateProvider extends DefaultSmartPredicateAnonymousExpressionBuilderProvider {
    @Override
    public void process(AnonymousEntityTableExpressionBuilder relation, PredicateSegment predicates) {
        // Even a filter on a plain projected key can change the input of another, unpartitioned window.
        if (relation.getEntityQueryExpressionBuilder().getProjects().getSQLSegments().stream().anyMatch(ExpressionScope::containsWindow))
            return;
        super.process(relation, predicates);
    }
}
