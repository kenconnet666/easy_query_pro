package org.dpdns.zerodep.easyquery.pro.test;

import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

final class PostgresTestDatabase {
    private PostgresTestDatabase() {
    }

    static PGSimpleDataSource dataSource() {
        var source = new PGSimpleDataSource();
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
        var owner = source.getConnection();
        try (var statement = owner.createStatement()) {
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

    record Schema(String name, Connection connection) implements AutoCloseable {
        String qualified(String table) {
            return "\"" + name + "\".\"" + table.replace("\"", "\"\"") + "\"";
        }

        @Override
        public void close() throws SQLException {
            try (var owner = connection) {
                // A failed assertion may leave the dedicated owner in an aborted transaction.
                if (!owner.getAutoCommit()) {
                    owner.rollback();
                    owner.setAutoCommit(true);
                }
                try (var statement = owner.createStatement()) {
                    statement.execute("DROP SCHEMA \"" + name + "\" CASCADE");
                }
            }
        }
    }
}
