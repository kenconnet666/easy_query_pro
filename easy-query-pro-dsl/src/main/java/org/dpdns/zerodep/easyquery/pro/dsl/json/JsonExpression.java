package org.dpdns.zerodep.easyquery.pro.dsl.json;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;

/**
 * Native JSON operations preserve the Java mapping of the original complete column value.
 */
public interface JsonExpression<T> extends PropTypeColumn<T>, DSLSQLFunctionAvailable {
    NativeJsonType sqlType();

    StringTypeExpression<String> asText();

    JsonFragmentExpression at(JsonPath path);

    StringTypeExpression<String> textAt(JsonPath path);

    BooleanTypeExpression<Boolean> booleanAt(JsonPath path);

    BooleanTypeExpression<Boolean> existsAt(JsonPath path);
}
