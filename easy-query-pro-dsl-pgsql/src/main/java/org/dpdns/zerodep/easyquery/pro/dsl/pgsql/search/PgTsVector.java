package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search;

import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

import java.util.Objects;

/**
 * PostgreSQL tsvector 的编码表示，不是待分词的原文。
 * 数据库会排序、去重和规范化其表示；Java equals 只比较当前编码，不冒充数据库语义相等。
 */
public record PgTsVector(String encoded) {
    public PgTsVector {
        Objects.requireNonNull(encoded, "encoded");
        UnicodeStrings.requirePortable(encoded, "PostgreSQL tsvector");
    }
}
