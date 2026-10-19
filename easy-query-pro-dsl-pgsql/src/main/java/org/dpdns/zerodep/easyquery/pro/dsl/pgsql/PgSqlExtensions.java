package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.pgsql.config.PgSQLSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExtensionInstaller;

public final class PgSqlExtensions {
    private PgSqlExtensions() {
    }

    public static void configure(ServiceCollection services) {
        ExtensionInstaller.install(services, SqlDialect.POSTGRESQL, PgSQLSQLKeyword.class);
    }
}
