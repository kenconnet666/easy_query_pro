package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.pgsql.config.PgSQLSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExtensionInstaller;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.NullSafeComparison;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;

public final class PgSqlExtensions {
    private PgSqlExtensions() {
    }

    public static void configure(ServiceCollection services) {
        ExtensionInstaller.install(services, SqlDialect.POSTGRESQL, PgSQLSQLKeyword.class,
                new NullSafeComparison("({0} IS NOT DISTINCT FROM {1})", "({0} IS DISTINCT FROM {1})"));
        services.addService(TemporalTruncation.class, new PgSqlTemporalTruncation());
        services.addService(JsonPathAccess.class, new PgSqlJsonPathAccess());
    }
}
