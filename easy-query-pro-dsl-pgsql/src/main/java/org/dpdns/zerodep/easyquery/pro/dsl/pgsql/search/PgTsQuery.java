package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search;

import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

import java.util.Objects;

/**
 * PostgreSQL tsquery 的编码表示。运算符/词素语法交数据库校验，不在 Java 中重写解析器。
 * 空编码是合法的空查询值，与 SQL NULL 分开；普通用户搜索文本应经 plain/websearch 构造。
 */
public record PgTsQuery(String encoded) {
    public PgTsQuery {
        Objects.requireNonNull(encoded, "encoded");
        UnicodeStrings.requirePortable(encoded, "PostgreSQL tsquery");
    }
}
