package org.dpdns.zerodep.easyquery.pro.dsl.mysql.json;

import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;

/**
 * MySQL native operations; path parents, array wrapping and NULL follow the database rules.
 */
public interface MySqlJsonExpression<T> extends JsonExpression<T> {
    BooleanTypeExpression<Boolean> containsJson(JsonInput candidate);

    BooleanTypeExpression<Boolean> containsJson(JsonInput candidate, JsonPath path);

    BooleanTypeExpression<Boolean> containsAnyPath(JsonPath... paths);

    BooleanTypeExpression<Boolean> containsAllPaths(JsonPath... paths);

    MySqlJsonExpression<T> setPath(JsonPath path, JsonInput value);

    /**
     * Changes are evaluated left to right; at least one change is required.
     */
    MySqlJsonExpression<T> setPaths(MySqlJsonChange... changes);

    MySqlJsonExpression<T> removePath(JsonPath path);

    /**
     * Paths are evaluated left to right; empty lists and root removal are rejected.
     */
    MySqlJsonExpression<T> removePaths(JsonPath... paths);
}
