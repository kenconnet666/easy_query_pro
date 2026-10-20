package org.dpdns.zerodep.easyquery.pro.test;

import com.mysql.cj.jdbc.MysqlDataSource;

import java.sql.SQLException;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;

final class MySqlTestDatabase {
    private MySqlTestDatabase() {
    }

    static MysqlDataSource dataSource() throws SQLException {
        var source = new MysqlDataSource();
        source.setURL(TestEnvironment.required("EQP_MYSQL_JDBC_URL"));
        source.setUser(TestEnvironment.required("EQP_MYSQL_USER"));
        source.setPassword(TestEnvironment.required("EQP_MYSQL_PASSWORD"));
        source.setConnectTimeout(10_000);
        source.setSocketTimeout(30_000);
        return source;
    }

    static Table createTable(DataSource source, String prefix, String columns) throws SQLException {
        if (!prefix.matches("[a-z_]{1,30}")) throw new IllegalArgumentException("Invalid test table prefix");
        String name = prefix + UUID.randomUUID().toString().replace("-", "");
        var owner = source.getConnection();
        try (var statement = owner.createStatement()) {
            statement.execute("CREATE TABLE `" + name + "` (" + columns + ") CHARACTER SET utf8mb4");
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                owner.close();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return new Table(name, owner);
    }

    record Table(String name, Connection connection) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            try (var owner = connection) {
                if (!owner.getAutoCommit()) {
                    owner.rollback();
                    owner.setAutoCommit(true);
                }
                try (var statement = owner.createStatement()) {
                    statement.execute("DROP TABLE `" + name + "`");
                }
            }
        }
    }
}
