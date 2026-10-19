package org.dpdns.zerodep.easyquery.pro.hana.config;

import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.executor.EntityExpressionPrepareExecutor;
import com.easy.query.core.basic.jdbc.conn.EasyConnectionFactory;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import com.easy.query.core.expression.sql.expression.factory.ExpressionFactory;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.sql.JdbcSQLExecutor;
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaJdbcSQLExecutor;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaJdbcTypeHandlerManager;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaInsertExecution;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaConnectionFactory;
import org.dpdns.zerodep.easyquery.pro.hana.function.HanaSQLFunc;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaCatalog;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaEntityMetadataManager;
import com.easy.query.core.metadata.EntityMetadataManager;
import com.easy.query.core.migration.DatabaseMigrationProvider;
import com.easy.query.core.migration.MigrationEntityParser;
import com.easy.query.core.migration.MigrationsSQLGenerator;
import com.easy.query.core.basic.api.database.DatabaseCodeFirst;
import org.dpdns.zerodep.easyquery.pro.hana.migration.HanaDatabaseCodeFirst;
import org.dpdns.zerodep.easyquery.pro.hana.migration.HanaDatabaseMigrationProvider;
import org.dpdns.zerodep.easyquery.pro.hana.migration.HanaMigrationEntityParser;
import org.dpdns.zerodep.easyquery.pro.hana.migration.HanaMigrationsSQLGenerator;

import java.util.Objects;

/**
 * Installs the HANA backend without connecting to the database.
 */
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
        services.addService(EntityExpressionPrepareExecutor.class, HanaInsertExecution.class);
        services.addService(EasyConnectionFactory.class, HanaConnectionFactory.class);
        services.addService(HanaCatalog.class);
        services.addService(EntityMetadataManager.class, HanaEntityMetadataManager.class);
        services.addService(MigrationEntityParser.class, HanaMigrationEntityParser.class);
        services.addService(DatabaseMigrationProvider.class, HanaDatabaseMigrationProvider.class);
        services.addService(MigrationsSQLGenerator.class, HanaMigrationsSQLGenerator.class);
        services.addService(DatabaseCodeFirst.class, HanaDatabaseCodeFirst.class);
    }
}
