package org.dpdns.zerodep.easyquery.pro.dsl.type;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

/**
 * A SQL conversion target. Implementations must be immutable; {0} denotes the bound source expression.
 */
public interface CastTarget<T> {
    Class<T> javaType();

    String castTemplate(SqlDialect dialect);

    /**
     * Validates a typed parameter and returns its lossless JDBC representation. SQL NULL remains null.
     */
    T prepareValue(T value);
}
