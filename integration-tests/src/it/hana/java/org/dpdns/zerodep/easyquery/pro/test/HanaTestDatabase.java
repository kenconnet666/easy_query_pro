package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.sap.db.jdbcext.HanaDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

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
        // 每次构建独立 client，使函数选项与服务注册不会在测试之间相互污染。
        return new DefaultEasyEntityQuery(EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(dataSource())
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
}
