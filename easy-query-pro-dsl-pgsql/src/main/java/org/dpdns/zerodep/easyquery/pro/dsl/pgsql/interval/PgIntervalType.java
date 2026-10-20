package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CastTarget;
import org.dpdns.zerodep.easyquery.pro.dsl.type.StorageType;

public final class PgIntervalType implements StorageType<PgInterval>, CastTarget<PgInterval> {
    public static final PgIntervalType INSTANCE = new PgIntervalType();

    private PgIntervalType() {
    }

    @Override
    public Class<PgInterval> javaType() {
        return PgInterval.class;
    }

    @Override
    public PgInterval prepareValue(PgInterval value) {
        return value;
    }

    @Override
    public String ddlType(SqlDialect dialect) {
        if (dialect != SqlDialect.POSTGRESQL) throw new IllegalArgumentException("INTERVAL requires PostgreSQL");
        return "INTERVAL";
    }

    @Override
    public String castTemplate(SqlDialect dialect) {
        return "CAST({0} AS " + ddlType(dialect) + ")";
    }
}
