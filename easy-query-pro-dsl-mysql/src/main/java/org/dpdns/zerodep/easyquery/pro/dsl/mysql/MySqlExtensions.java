package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.configuration.QueryConfiguration;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import org.dpdns.zerodep.easyquery.pro.dsl.jdbc.ExactBigIntegerTypeHandler;

import java.math.BigInteger;

import com.easy.query.mysql.config.MySQLSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExtensionInstaller;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.NullSafeComparison;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.MySqlJsonColumnConverter;

public final class MySqlExtensions {
    private MySqlExtensions() {
    }

    public static void configure(ServiceCollection services) {
        ExtensionInstaller.install(services, SqlDialect.MYSQL, MySQLSQLKeyword.class,
                new NullSafeComparison("({0} <=> {1})", "(NOT ({0} <=> {1}))"));
        services.addService(TemporalTruncation.class, new MySqlTemporalTruncation());
        services.addService(JsonPathAccess.class, new MySqlJsonPathAccess());
    }

    public static void configureMappings(QueryConfiguration configuration) {
        configuration.applyColumnValueSQLConverter(new MySqlJsonColumnConverter());
    }

    public static void configureTypeHandlers(JdbcTypeHandlerManager handlers) {
        handlers.appendHandler(BigInteger.class, new ExactBigIntegerTypeHandler(), false);
    }
}
