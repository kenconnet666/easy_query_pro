package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.sap.db.jdbcext.HanaDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Locale;

final class HanaTestDatabase {
    private HanaTestDatabase() {
    }

    static DataSource dataSource() throws SQLException {
        var dataSource = new HanaDataSource();
        dataSource.setUrl(TestEnvironment.required("EQP_HANA_JDBC_URL"));
        dataSource.setUser(TestEnvironment.required("EQP_HANA_USER"));
        dataSource.setPassword(TestEnvironment.required("EQP_HANA_PASSWORD"));
        dataSource.setLoginTimeout(10);
        return dataSource;
    }

    static Connection connect() throws SQLException {
        return dataSource().getConnection();
    }

    static HikariDataSource pool(int maximumConnections) throws SQLException {
        var config = new HikariConfig();
        config.setDataSource(dataSource());
        config.setPoolName(uniqueName("POOL"));
        config.setMaximumPoolSize(maximumConnections);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        return new HikariDataSource(config);
    }

    static DefaultEasyEntityQuery client() throws SQLException {
        return client(HanaOptions.DEFAULT);
    }

    static DefaultEasyEntityQuery client(HanaOptions options) throws SQLException {
        return client(dataSource(), options);
    }

    static DefaultEasyEntityQuery client(DataSource source) {
        return client(source, HanaOptions.DEFAULT);
    }

    private static DefaultEasyEntityQuery client(DataSource source, HanaOptions options) {
        // 每次构建独立 client，使函数选项与服务注册不会在测试之间相互污染。
        return new DefaultEasyEntityQuery(EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(source)
                .useDatabaseConfigure(new HanaDatabaseConfiguration(options)).build());
    }

    static String uniqueName(String prefix) {
        return "EQP_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    static TestTable createTable(String prefix, String columns) throws SQLException {
        String name = uniqueName(prefix);
        var connection = connect();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE COLUMN TABLE \"" + name + "\" (" + columns + ")");
            return new TestTable(name, connection);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    record TestTable(String name, Connection connection) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            // 表名由本测试设施创建，资源作用域结束时只清理这一张表。
            try (connection; var statement = connection.createStatement()) {
                statement.execute("DROP TABLE \"" + name + "\"");
            }
        }
    }

    static TestSchema createSchema(String prefix) throws SQLException {
        String name = uniqueName(prefix);
        var connection = connect();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + name + "\"");
            return new TestSchema(name, connection);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    record TestSchema(String name, Connection connection) implements AutoCloseable {
        String qualified(String object) {
            var keyword = new HanaSQLKeyword();
            return keyword.quoteUnquotedName(name) + "." + keyword.quoteUnquotedName(object);
        }

        @Override
        public void close() throws SQLException {
            try (connection; var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA \"" + name + "\" CASCADE");
            }
        }
    }

    static TestUser createUser(String prefix) throws SQLException {
        String name = uniqueName(prefix).toUpperCase(Locale.ROOT);
        String password = "Eqp9!" + UUID.randomUUID().toString().replace("-", "");
        var source = new HanaDataSource();
        source.setUrl(TestEnvironment.required("EQP_HANA_JDBC_URL"));
        source.setUser(name);
        source.setPassword(password);
        source.setLoginTimeout(10);
        var admin = connect();
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE USER " + name + " PASSWORD \"" + password + "\" NO FORCE_FIRST_PASSWORD_CHANGE");
            return new TestUser(name, source, admin);
        } catch (SQLException | RuntimeException failure) {
            try {
                admin.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    record TestUser(String name, DataSource dataSource, Connection admin) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            try (admin; var statement = admin.createStatement()) {
                statement.execute("DROP USER " + name + " CASCADE");
            }
        }
    }
}
