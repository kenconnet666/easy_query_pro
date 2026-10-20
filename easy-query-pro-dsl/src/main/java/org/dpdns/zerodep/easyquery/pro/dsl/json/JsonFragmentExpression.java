package org.dpdns.zerodep.easyquery.pro.dsl.json;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;

/**
 * A JSON value. Projection reads encoded JSON; text operations require explicit serialization.
 */
public interface JsonFragmentExpression extends PropTypeColumn<String>, DSLSQLFunctionAvailable {
    StringTypeExpression<String> asText();
}
