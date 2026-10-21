package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query;

import com.easy.query.core.expression.segment.scec.context.SQLNativeExpressionContext;
import com.easy.query.core.proxy.SQLColumn;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsQuery;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsVector;

import java.util.List;
import java.util.Objects;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;
import com.easy.query.core.util.EasyStringUtil;

/**
 * 对已映射 tsvector 列的不可变检索规格。词典配置必须与构建该列时的配置一致。
 *
 * <p>普通文本与 websearch 交 PostgreSQL 分词；已编码 PgTsQuery 不再分词。
 * 归一化保持原生位组合语义，不把 rank 解释成概率，也不承诺跨库分数可比。
 */
public final class PgSearchSpec {
    public enum Ranking {
        FREQUENCY("ts_rank"),
        COVER_DENSITY("ts_rank_cd");

        private final String function;

        Ranking(String function) {
            this.function = function;
        }
    }

    public enum Normalization {
        LOG_DOCUMENT_LENGTH(1),
        DOCUMENT_LENGTH(2),
        /**
         * 只适用于 COVER_DENSITY，依赖未被 strip 的词素位置信息。
         */
        MEAN_EXTENT_DISTANCE(4),
        UNIQUE_WORDS(8),
        LOG_UNIQUE_WORDS(16),
        RANK_OVER_RANK_PLUS_ONE(32);

        private final int flag;

        Normalization(int flag) {
            this.flag = flag;
        }
    }

    private final SQLColumn<?, PgTsVector> vector;
    private final String querySql;
    private final List<?> queryParameters;
    private final Ranking ranking;
    private final int normalization;

    private PgSearchSpec(SQLColumn<?, PgTsVector> vector, String querySql, List<?> queryParameters,
                         Ranking ranking, int normalization) {
        this.vector = Objects.requireNonNull(vector, "vector");
        this.querySql = querySql;
        this.queryParameters = queryParameters;
        this.ranking = ranking;
        this.normalization = normalization;
    }

    /**
     * 普通文本的剩余词素以 AND 组合；不会把输入中的 {@code &}、{@code |} 或前缀标签当 tsquery 运算符。
     */
    public static PgSearchSpec plain(String configuration, String searchText, SQLColumn<?, PgTsVector> vector) {
        return text(configuration, searchText, vector, "plainto_tsquery");
    }

    /**
     * PostgreSQL websearch 语法支持短语、OR 和减号；不是通用搜索引擎语法解析器。
     */
    public static PgSearchSpec web(String configuration, String searchText, SQLColumn<?, PgTsVector> vector) {
        return text(configuration, searchText, vector, "websearch_to_tsquery");
    }

    /**
     * 已经按 PostgreSQL 词素/运算符编码的查询；空编码合法，NULL 查询不隐式省略筛选。
     */
    public static PgSearchSpec query(PgTsQuery query, SQLColumn<?, PgTsVector> vector) {
        return new PgSearchSpec(vector, "CAST({1} AS tsquery)", ImmutableCollections.listOf(Objects.requireNonNull(query, "query")),
                Ranking.FREQUENCY, 0);
    }

    public PgSearchSpec rankedBy(Ranking ranking, Normalization... normalization) {
        Objects.requireNonNull(ranking, "ranking");
        int mask = 0;
        for (PgSearchSpec.Normalization value : Objects.requireNonNull(normalization, "normalization"))
            mask |= Objects.requireNonNull(value, "normalization value").flag;
        if (ranking != Ranking.COVER_DENSITY && (mask & Normalization.MEAN_EXTENT_DISTANCE.flag) != 0)
            throw new IllegalArgumentException("Mean extent distance normalization requires COVER_DENSITY ranking");
        return new PgSearchSpec(vector, querySql, queryParameters, ranking, mask);
    }

    private static PgSearchSpec text(String configuration, String text, SQLColumn<?, PgTsVector> vector, String function) {
        requireText(configuration, "PostgreSQL text search configuration");
        requireText(text, "PostgreSQL search text");
        // function 只来自本类固定入口；配置名与用户文本均保留为 JDBC 参数。
        return new PgSearchSpec(vector, function + "(CAST({1} AS regconfig),CAST({2} AS text))",
                ImmutableCollections.listOf(configuration, text), Ranking.FREQUENCY, 0);
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        UnicodeStrings.requirePortable(value, name);
        if (EasyStringUtil.isBlank(value)) throw new IllegalArgumentException(name + " must not be blank");
    }

    SQLColumn<?, PgTsVector> vector() {
        return vector;
    }

    String predicateSql() {
        return "{0} @@ " + querySql;
    }

    String scoreSql() {
        return "CAST(" + ranking.function + "({0}," + querySql + ",{" + (queryParameters.size() + 1)
                + "}) AS double precision)";
    }

    void bind(SQLNativeExpressionContext context, boolean score) {
        context.expression(vector.getTable(), vector.getValue());
        queryParameters.forEach(context::value);
        if (score) context.value(normalization);
    }
}
