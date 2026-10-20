package org.dpdns.zerodep.easyquery.pro.dsl.json;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ScalarComparisonRestriction;

/**
 * A JSON value. Projection reads encoded JSON; text operations require explicit serialization.
 */
public interface JsonFragmentExpression extends PropTypeColumn<String>, DSLSQLFunctionAvailable, ScalarComparisonRestriction {
    @Override
    default String scalarComparisonError() {
        return "JSON values require explicit text comparison or native JSON operators";
    }

    StringTypeExpression<String> asText();
}
