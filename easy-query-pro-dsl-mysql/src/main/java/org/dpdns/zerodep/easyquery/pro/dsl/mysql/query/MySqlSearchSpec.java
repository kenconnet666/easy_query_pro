package org.dpdns.zerodep.easyquery.pro.dsl.mysql.query;

import com.easy.query.core.proxy.SQLColumn;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 不可变 MATCH 规格。列集合必须有对应 FULLTEXT 索引；布尔语法由 MySQL 解释，不改写为 LIKE。
 */
public final class MySqlSearchSpec {
    private final List<SQLColumn<?, String>> columns;
    private final String searchQuery;
    private final boolean booleanMode;

    private MySqlSearchSpec(String searchQuery, boolean booleanMode, SQLColumn<?, String> first, SQLColumn<?, String>[] more) {
        Objects.requireNonNull(searchQuery, "searchQuery");
        UnicodeStrings.requirePortable(searchQuery, "MySQL search query");
        if (searchQuery.isBlank()) throw new IllegalArgumentException("MySQL search query must not be blank");
        var selected = new ArrayList<SQLColumn<?, String>>();
        selected.add(Objects.requireNonNull(first, "first"));
        selected.addAll(Arrays.asList(Objects.requireNonNull(more, "more")));
        this.columns = List.copyOf(selected);
        this.searchQuery = searchQuery;
        this.booleanMode = booleanMode;
    }

    @SafeVarargs
    public static MySqlSearchSpec naturalLanguage(String searchQuery, SQLColumn<?, String> first, SQLColumn<?, String>... more) {
        return new MySqlSearchSpec(searchQuery, false, first, more);
    }

    @SafeVarargs
    public static MySqlSearchSpec booleanMode(String searchQuery, SQLColumn<?, String> first, SQLColumn<?, String>... more) {
        return new MySqlSearchSpec(searchQuery, true, first, more);
    }

    List<SQLColumn<?, String>> columns() {
        return columns;
    }

    String searchQuery() {
        return searchQuery;
    }

    String mode() {
        return booleanMode ? "BOOLEAN" : "NATURAL LANGUAGE";
    }
}
