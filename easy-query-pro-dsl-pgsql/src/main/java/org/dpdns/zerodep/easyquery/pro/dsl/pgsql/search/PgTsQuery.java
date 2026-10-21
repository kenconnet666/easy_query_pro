package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search;

import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

import java.util.Objects;

/**
 * PostgreSQL tsquery 的编码表示。运算符/词素语法交数据库校验，不在 Java 中重写解析器。
 * 空编码是合法的空查询值，与 SQL NULL 分开；普通用户搜索文本应经 plain/websearch 构造。
 */
public final class PgTsQuery {
    private final String encoded;

    public PgTsQuery(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        UnicodeStrings.requirePortable(encoded, "PostgreSQL tsquery");
        this.encoded = encoded;
    }

    public String encoded() {
        return encoded;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PgTsQuery)) return false;
        PgTsQuery other = (PgTsQuery) o;
        return Objects.equals(encoded, other.encoded);
    }

    @Override
    public int hashCode() {
        return Objects.hash(encoded);
    }

    @Override
    public String toString() {
        return "PgTsQuery[encoded=" + encoded + "]";
    }
}
