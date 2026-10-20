package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json;

import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

import java.util.Objects;

/**
 * PostgreSQL jsonpath syntax, bound as data and parsed by PostgreSQL.
 */
public record PgJsonPath(String expression) {
    public PgJsonPath {
        Objects.requireNonNull(expression, "expression");
        UnicodeStrings.requirePortable(expression, "PostgreSQL jsonpath");
        if (expression.isBlank()) throw new IllegalArgumentException("PostgreSQL jsonpath cannot be blank");
    }
}
