package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;

import java.sql.SQLException;
import java.util.UUID;

class PostgresConnectionIT {
    @Test
    void upstreamAdapterWorksWithTheConfiguredPostgres() throws SQLException {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(TestEnvironment.required("EQP_PG_JDBC_URL"));
        dataSource.setUser(TestEnvironment.required("EQP_PG_USER"));
        dataSource.setPassword(TestEnvironment.required("EQP_PG_PASSWORD"));
        dataSource.setConnectTimeout(10);
        dataSource.setSocketTimeout(30);
        String schema = "eqp_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                dataSource.setCurrentSchema(schema);
                JdbcContract.verify(dataSource, new PgSQLDatabaseConfiguration());
                DecimalDslContract.verify(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
                NullSafeDslContract.verify(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
                NullIfDslContract.verify(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
