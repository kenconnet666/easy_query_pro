package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import com.easy.query.core.proxy.SQLColumn;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 一个或多个按 AND 组合的 HANA 全文条件。搜索文本使用数据库 freestyle 语法，不能当作 LIKE 字面量。
 * SCORE 是整组搜索条件的综合评分；FUZZY 阈值表示词匹配容差，不是最终评分的下限。
 */
public final class HanaSearchSpec {
    private final List<Clause> clauses;

    private HanaSearchSpec(List<Clause> clauses) {
        this.clauses = List.copyOf(clauses);
        if (clauses.stream().filter(clause -> clause.columns().size() > 1).count() > 1)
            throw new IllegalArgumentException("HANA allows at most one multi-column CONTAINS per search");
    }

    @SafeVarargs
    public static HanaSearchSpec exact(String searchQuery, SQLColumn<?, String> first, SQLColumn<?, String>... more) {
        return single(searchQuery, null, first, more);
    }

    @SafeVarargs
    public static HanaSearchSpec fuzzy(String searchQuery, double threshold, SQLColumn<?, String> first, SQLColumn<?, String>... more) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)
            throw new IllegalArgumentException("HANA fuzzy threshold must be finite and between 0 and 1");
        return single(searchQuery, threshold, first, more);
    }

    public HanaSearchSpec and(HanaSearchSpec other) {
        var combined = new ArrayList<>(clauses);
        combined.addAll(Objects.requireNonNull(other, "other").clauses);
        return new HanaSearchSpec(combined);
    }

    private static HanaSearchSpec single(String searchQuery, Double threshold, SQLColumn<?, String> first, SQLColumn<?, String>[] more) {
        Objects.requireNonNull(searchQuery, "searchQuery");
        UnicodeStrings.requirePortable(searchQuery, "HANA search query");
        if (searchQuery.isBlank()) throw new IllegalArgumentException("HANA search query must not be blank");
        var columns = new ArrayList<SQLColumn<?, String>>();
        columns.add(Objects.requireNonNull(first, "first"));
        columns.addAll(Arrays.asList(Objects.requireNonNull(more, "more")));
        return new HanaSearchSpec(List.of(new Clause(List.copyOf(columns), searchQuery, threshold)));
    }

    List<Clause> clauses() {
        return clauses;
    }

    record Clause(List<SQLColumn<?, String>> columns, String searchQuery, Double threshold) {
    }
}
