package org.dpdns.zerodep.easyquery.pro.hana.config;

import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import com.easy.query.core.expression.sql.expression.factory.ExpressionFactory;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.sql.JdbcSQLExecutor;
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaJdbcSQLExecutor;

/** Installs the HANA backend without connecting to the database. */
public final class HanaDatabaseConfiguration implements DatabaseConfiguration {
    @Override
    public void configure(ServiceCollection services) {
        services.addService(SQLKeyword.class, HanaSQLKeyword.class);
        services.addService(ExpressionFactory.class, HanaExpressionFactory.class);
        services.addService(JdbcSQLExecutor.class, HanaJdbcSQLExecutor.class);
    }
}
