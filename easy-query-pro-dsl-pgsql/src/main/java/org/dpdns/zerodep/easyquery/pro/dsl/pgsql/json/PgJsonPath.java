package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json;

import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

import java.util.Objects;
import com.easy.query.core.util.EasyStringUtil;

/**
 * PostgreSQL jsonpath syntax, bound as data and parsed by PostgreSQL.
 */
public final class PgJsonPath {
    private final String expression;

    public PgJsonPath(String expression) {
        Objects.requireNonNull(expression, "expression");
        UnicodeStrings.requirePortable(expression, "PostgreSQL jsonpath");
        if (EasyStringUtil.isBlank(expression)) throw new IllegalArgumentException("PostgreSQL jsonpath cannot be blank");
        this.expression = expression;
    }

    public String expression() {
        return expression;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PgJsonPath)) return false;
        PgJsonPath other = (PgJsonPath) o;
        return Objects.equals(expression, other.expression);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expression);
    }

    @Override
    public String toString() {
        return "PgJsonPath[expression=" + expression + "]";
    }
}
