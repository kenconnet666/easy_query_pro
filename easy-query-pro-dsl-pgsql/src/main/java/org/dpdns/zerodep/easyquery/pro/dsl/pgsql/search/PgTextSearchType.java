package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CastTarget;
import org.dpdns.zerodep.easyquery.pro.dsl.type.StorageType;

import java.util.function.Function;

/**
 * 受控的两个 PostgreSQL 全文物理类型；T 保留 vector/query 的编译区别。
 */
public final class PgTextSearchType<T> implements StorageType<T>, CastTarget<T> {
    public static final PgTextSearchType<PgTsVector> VECTOR = new PgTextSearchType<>(
            "tsvector", PgTsVector.class, PgTsVector::new, PgTsVector::encoded);
    public static final PgTextSearchType<PgTsQuery> QUERY = new PgTextSearchType<>(
            "tsquery", PgTsQuery.class, PgTsQuery::new, PgTsQuery::encoded);

    private final String sqlName;
    private final Class<T> javaType;
    private final Function<String, T> decoder;
    private final Function<T, String> encoder;

    private PgTextSearchType(String sqlName, Class<T> javaType, Function<String, T> decoder, Function<T, String> encoder) {
        this.sqlName = sqlName;
        this.javaType = javaType;
        this.decoder = decoder;
        this.encoder = encoder;
    }

    @Override
    public Class<T> javaType() {
        return javaType;
    }

    @Override
    public T prepareValue(T value) {
        return value;
    }

    @Override
    public String ddlType(SqlDialect dialect) {
        if (dialect != SqlDialect.POSTGRESQL) throw new IllegalArgumentException(sqlName + " requires PostgreSQL");
        return sqlName;
    }

    @Override
    public String castTemplate(SqlDialect dialect) {
        return "CAST({0} AS " + ddlType(dialect) + ")";
    }

    String sqlName() {
        return sqlName;
    }

    T decode(String text) {
        return decoder.apply(text);
    }

    String encode(T value) {
        return encoder.apply(value);
    }
}
