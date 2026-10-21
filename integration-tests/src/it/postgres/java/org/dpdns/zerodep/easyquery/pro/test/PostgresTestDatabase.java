package org.dpdns.zerodep.easyquery.pro.test;

import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.sql.Statement;
import java.util.Objects;

final class PostgresTestDatabase {
    private PostgresTestDatabase() {
    }

    static PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(TestEnvironment.required("EQP_PG_JDBC_URL"));
        source.setUser(TestEnvironment.required("EQP_PG_USER"));
        source.setPassword(TestEnvironment.required("EQP_PG_PASSWORD"));
        source.setConnectTimeout(10);
        source.setSocketTimeout(30);
        return source;
    }

    static Schema createSchema(PGSimpleDataSource source, String prefix) throws SQLException {
        if (!prefix.matches("[a-z_]{1,30}")) throw new IllegalArgumentException("Invalid test schema prefix");
        String name = prefix + UUID.randomUUID().toString().replace("-", "");
        Connection owner = source.getConnection();
        try (Statement statement = owner.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + name + "\"");
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                owner.close();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        source.setCurrentSchema(name);
        return new Schema(name, owner);
    }

    static final class Schema implements AutoCloseable {
        private final String name;
        private final Connection connection;

        Schema(String name, Connection connection) {
            this.name = name;
            this.connection = connection;
        }
        String qualified(String table) {
            return "\"" + name + "\".\"" + table.replace("\"", "\"\"") + "\"";
        }

        @Override
        public void close() throws SQLException {
            try (Connection owner = connection) {
                // A failed assertion may leave the dedicated owner in an aborted transaction.
                if (!owner.getAutoCommit()) {
                    owner.rollback();
                    owner.setAutoCommit(true);
                }
                try (Statement statement = owner.createStatement()) {
                    statement.execute("DROP SCHEMA \"" + name + "\" CASCADE");
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
            if (!(o instanceof Schema)) return false;
            Schema other = (Schema) o;
            return Objects.equals(name, other.name)
                    && Objects.equals(connection, other.connection);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, connection);
        }

        @Override
        public String toString() {
            return "Schema[name=" + name + ", connection=" + connection + "]";
        }
    }
}
