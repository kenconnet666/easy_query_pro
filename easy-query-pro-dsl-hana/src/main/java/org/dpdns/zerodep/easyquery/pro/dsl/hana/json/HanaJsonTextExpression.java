package org.dpdns.zerodep.easyquery.pro.dsl.hana.json;

import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonExpression;

/**
 * Common JSON reads over declared text storage, with no JSONB or native mutation methods.
 */
public interface HanaJsonTextExpression<T> extends JsonExpression<T> {
}
