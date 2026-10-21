package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.Statement;

class PostgresQueryResourceIT {
    @Test
    void streamingFailuresAndConcurrentReadersReleaseTheirOwnResources() throws Exception {
        PGSimpleDataSource source = PostgresTestDatabase.dataSource();
        try (PostgresTestDatabase.Schema schema = PostgresTestDatabase.createSchema(source, "eqp_resources_")) {
            try (Statement statement = schema.connection().createStatement()) {
                statement.execute("CREATE TABLE " + schema.qualified("eqp_product")
                        + " (id INTEGER PRIMARY KEY,name VARCHAR(100),amount DECIMAL(19,4))");
            }
            QueryResourceContract.seed(schema.connection(), schema.qualified("eqp_product"));
            QueryResourceContract.verify(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure, "eqp_product", null);
        }
    }
}
