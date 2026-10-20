package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

class PostgresShardingDslIT {
    @Test
    void rowLocalExpressionsCanBeEvaluatedBeforeShardMerge() throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        try (var schema = PostgresTestDatabase.createSchema(source, "eqp_shard_"); var ddl = schema.connection().createStatement()) {
            var tables = List.of("first_shard", "second_shard");
            for (String table : tables)
                ddl.execute("CREATE TABLE " + schema.qualified(table) + " (id INTEGER PRIMARY KEY, name VARCHAR(200), amount DECIMAL(19,4))");
            ShardingDslContract.verify(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure, tables);
        }
    }
}
