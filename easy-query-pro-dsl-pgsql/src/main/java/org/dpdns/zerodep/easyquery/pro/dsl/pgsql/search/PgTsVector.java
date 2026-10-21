package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search;

import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

import java.util.Objects;

/**
 * PostgreSQL tsvector 的编码表示，不是待分词的原文。
 * 数据库会排序、去重和规范化其表示；Java equals 只比较当前编码，不冒充数据库语义相等。
 */
public final class PgTsVector {
    private final String encoded;

    public PgTsVector(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        UnicodeStrings.requirePortable(encoded, "PostgreSQL tsvector");
        this.encoded = encoded;
    }

    public String encoded() {
        return encoded;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PgTsVector)) return false;
        PgTsVector other = (PgTsVector) o;
        return Objects.equals(encoded, other.encoded);
    }

    @Override
    public int hashCode() {
        return Objects.hash(encoded);
    }

    @Override
    public String toString() {
        return "PgTsVector[encoded=" + encoded + "]";
    }
}
