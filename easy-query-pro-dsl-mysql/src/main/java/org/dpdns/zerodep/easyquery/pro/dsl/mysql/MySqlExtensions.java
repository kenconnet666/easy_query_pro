package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.mysql.config.MySQLSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExtensionInstaller;

public final class MySqlExtensions {
    private MySqlExtensions() {
    }

    public static void configure(ServiceCollection services) {
        ExtensionInstaller.install(services, SqlDialect.MYSQL, MySQLSQLKeyword.class);
    }
}
