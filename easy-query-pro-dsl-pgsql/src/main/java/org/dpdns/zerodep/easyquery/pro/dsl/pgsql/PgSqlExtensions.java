package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.pgsql.config.PgSQLSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExtensionInstaller;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.NullSafeComparison;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import com.easy.query.core.configuration.QueryConfiguration;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayTypeHandler;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayValue;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.internal.PgScalarMetadataManager;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgIntervalTypeHandler;
import com.easy.query.core.metadata.EntityMetadataManager;
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
        services.addService(EntityMetadataManager.class, PgScalarMetadataManager.class);
    }

    public static void configureMappings(QueryConfiguration configuration) {
        configuration.applyColumnValueSQLConverter(new PgJsonColumnConverter());
        configuration.applyColumnValueSQLConverter(new PgJsonbColumnConverter());
    }

    public static void configureTypeHandlers(JdbcTypeHandlerManager handlers) {
        handlers.appendHandler(PgArrayValue.class, new PgArrayTypeHandler(), false);
        var interval = new PgIntervalTypeHandler();
        handlers.appendHandler(PgInterval.class, interval, false);
        handlers.appendHandler(PgInterval.Finite.class, interval, false);
        handlers.appendHandler(PgInterval.Infinity.class, interval, false);
    }
}
