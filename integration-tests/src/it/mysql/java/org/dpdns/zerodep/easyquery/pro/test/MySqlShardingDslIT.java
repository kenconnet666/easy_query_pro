package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

class MySqlShardingDslIT {
    @Test
    void rowLocalExpressionsCanBeEvaluatedBeforeShardMerge() throws SQLException {
        var source = MySqlTestDatabase.dataSource();
        String columns = "id INTEGER PRIMARY KEY, name VARCHAR(200), amount DECIMAL(19,4), created DATETIME(6)";
        try (var first = MySqlTestDatabase.createTable(source, "eqp_shard_a_", columns);
             var second = MySqlTestDatabase.createTable(source, "eqp_shard_b_", columns)) {
            ShardingDslContract.verify(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure, List.of(first.name(), second.name()));
        }
    }
}
