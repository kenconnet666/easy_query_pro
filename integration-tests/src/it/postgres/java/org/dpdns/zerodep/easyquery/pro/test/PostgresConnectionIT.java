package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;

import java.sql.SQLException;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.Connection;
import java.sql.Statement;

class PostgresConnectionIT {
    @Test
    void upstreamAdapterWorksWithTheConfiguredPostgres() throws SQLException {
        PGSimpleDataSource dataSource = PostgresTestDatabase.dataSource();
        String schema = "eqp_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                dataSource.setCurrentSchema(schema);
                JdbcContract.verify(dataSource, new PgSQLDatabaseConfiguration(), CommonTypes.unicodeText(255).ddlType(SqlDialect.POSTGRESQL));
                DecimalDslContract.verify(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
                NullSafeDslContract.verify(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
                NullIfDslContract.verify(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
                TemporalTruncationDslContract.verify(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
                TextDslContract.verify(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
                JsonPathDslContract.verify(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
