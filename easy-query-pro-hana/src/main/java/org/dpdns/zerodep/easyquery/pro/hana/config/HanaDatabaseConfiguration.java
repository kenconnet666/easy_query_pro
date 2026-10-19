package org.dpdns.zerodep.easyquery.pro.hana.config;

import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import com.easy.query.core.expression.sql.expression.factory.ExpressionFactory;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.sql.JdbcSQLExecutor;
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaJdbcSQLExecutor;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaJdbcTypeHandlerManager;
import org.dpdns.zerodep.easyquery.pro.hana.function.HanaSQLFunc;

import java.util.Objects;

/** Installs the HANA backend without connecting to the database. */
public final class HanaDatabaseConfiguration implements DatabaseConfiguration {
    private final HanaOptions options;

    public HanaDatabaseConfiguration() {
        this(HanaOptions.DEFAULT);
    }

    public HanaDatabaseConfiguration(HanaOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public void configure(ServiceCollection services) {
        services.addService(SQLKeyword.class, HanaSQLKeyword.class);
        services.addService(ExpressionFactory.class, HanaExpressionFactory.class);
        services.addServiceFactory(SQLFunc.class, provider -> new HanaSQLFunc(options));
        services.addService(JdbcSQLExecutor.class, HanaJdbcSQLExecutor.class);
        services.addService(JdbcTypeHandlerManager.class, HanaJdbcTypeHandlerManager.class);
    }
}
