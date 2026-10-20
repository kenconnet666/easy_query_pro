package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.pgsql.config.PgSQLSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExtensionInstaller;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.NullSafeComparison;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import com.easy.query.core.configuration.QueryConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonbColumnConverter;

public final class PgSqlExtensions {
    private PgSqlExtensions() {
    }

    public static void configure(ServiceCollection services) {
        ExtensionInstaller.install(services, SqlDialect.POSTGRESQL, PgSQLSQLKeyword.class,
                new NullSafeComparison("({0} IS NOT DISTINCT FROM {1})", "({0} IS DISTINCT FROM {1})"));
        services.addService(TemporalTruncation.class, new PgSqlTemporalTruncation());
        services.addService(JsonPathAccess.class, new PgSqlJsonPathAccess());
    }

    public static void configureMappings(QueryConfiguration configuration) {
        configuration.applyColumnValueSQLConverter(new PgJsonColumnConverter());
        configuration.applyColumnValueSQLConverter(new PgJsonbColumnConverter());
    }
}
