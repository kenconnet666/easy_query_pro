package org.dpdns.zerodep.easyquery.pro.test;

import com.mysql.cj.jdbc.MysqlDataSource;

import java.sql.SQLException;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import java.sql.Statement;
import java.util.Objects;

final class MySqlTestDatabase {
    private MySqlTestDatabase() {
    }

    static MysqlDataSource dataSource() throws SQLException {
        MysqlDataSource source = new MysqlDataSource();
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
        Connection owner = source.getConnection();
        try (Statement statement = owner.createStatement()) {
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

    static final class Table implements AutoCloseable {
        private final String name;
        private final Connection connection;

        Table(String name, Connection connection) {
            this.name = name;
            this.connection = connection;
        }
        @Override
        public void close() throws SQLException {
            try (Connection owner = connection) {
                if (!owner.getAutoCommit()) {
                    owner.rollback();
                    owner.setAutoCommit(true);
                }
                try (Statement statement = owner.createStatement()) {
                    statement.execute("DROP TABLE `" + name + "`");
                }
            }
        }

        public String name() {
            return name;
        }

        public Connection connection() {
            return connection;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Table)) return false;
            Table other = (Table) o;
            return Objects.equals(name, other.name)
                    && Objects.equals(connection, other.connection);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, connection);
        }

        @Override
        public String toString() {
            return "Table[name=" + name + ", connection=" + connection + "]";
        }
    }
}
