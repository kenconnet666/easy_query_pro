package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json;

import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;

import java.util.List;

public interface PgJsonbExpression<T> extends PgJsonExpression<T> {
    BooleanTypeExpression<Boolean> containsJson(JsonInput candidate);

    BooleanTypeExpression<Boolean> containedBy(JsonInput container);

    BooleanTypeExpression<Boolean> hasKey(String key);

    BooleanTypeExpression<Boolean> hasAnyKey(String... keys);

    BooleanTypeExpression<Boolean> hasAllKeys(String... keys);

    BooleanTypeExpression<Boolean> pathExists(PgJsonPath path);

    BooleanTypeExpression<Boolean> pathExists(PgJsonPath path, JsonInput variables, boolean silent);

    PgJsonbExpression<T> setPath(List<String> path, JsonInput value, boolean createMissing);

    PgJsonbExpression<T> removeKey(String key);

    PgJsonbExpression<T> removePath(List<String> path);
}
