package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json;

import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonExpression;

public interface PgJsonExpression<T> extends JsonExpression<T> {
    PgJsonbExpression<T> toJsonb();
}
