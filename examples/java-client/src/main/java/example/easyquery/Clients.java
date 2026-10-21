package example.easyquery;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import com.easy.query.core.configuration.QueryConfiguration;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;

import javax.sql.DataSource;
import java.util.function.Consumer;
import com.easy.query.core.api.client.EasyQueryClient;

/**
 * 示例中同时支持三库的安装位置；实际应用只需保留采用的分支和依赖。
 */
public final class Clients {
    public enum Database {
        MYSQL(new MySQLDatabaseConfiguration(), services -> {
            MySqlExtensions.configure(services);
            MySqlExtensions.configureQueryScope(services);
        }, MySqlExtensions::configureMappings, MySqlExtensions::configureTypeHandlers),
        POSTGRES(new PgSQLDatabaseConfiguration(), services -> {
            PgSqlExtensions.configure(services);
            PgSqlExtensions.configureQueryScope(services);
        }, PgSqlExtensions::configureMappings, PgSqlExtensions::configureTypeHandlers),
        HANA(new HanaDatabaseConfiguration(), HanaExtensions::configure, configuration -> {
        }, handlers -> {
        });

        private final DatabaseConfiguration configuration;
        private final Consumer<ServiceCollection> services;
        private final Consumer<QueryConfiguration> mappings;
        private final Consumer<JdbcTypeHandlerManager> handlers;

        Database(DatabaseConfiguration configuration, Consumer<ServiceCollection> services,
                 Consumer<QueryConfiguration> mappings, Consumer<JdbcTypeHandlerManager> handlers) {
            this.configuration = configuration;
            this.services = services;
            this.mappings = mappings;
            this.handlers = handlers;
        }
    }

    private Clients() {
    }

    public static DefaultEasyEntityQuery create(DataSource source, Database database) {
        EasyQueryClient client = EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(source)
                .useDatabaseConfigure(database.configuration)
                .customConfigure(database.services)
                .build();
        // 必须先注册映射与 handler，再首次读取实体 metadata；应用自己的覆盖放在这些默认注册之后。
        database.mappings.accept(client.getRuntimeContext().getQueryConfiguration());
        database.handlers.accept(client.getRuntimeContext().getJdbcTypeHandlerManager());
        return new DefaultEasyEntityQuery(client);
    }
}
