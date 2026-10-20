package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json;

import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonExpression;

/**
 * PG JSON 保留原文档映射；JSONB 专属比较和修改通过显式转换后获得。
 */
public interface PgJsonExpression<T> extends JsonExpression<T> {
    /**
     * 生成实际 JSONB 转换，继续保留整列业务 T；不是只改变 Java 接口而沿用 JSON 物理类型。
     */
    PgJsonbExpression<T> toJsonb();
}
