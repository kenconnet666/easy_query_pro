package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.hana.json.HanaJsonSupport;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

class HanaShardingDslIT {
    @Test
    void rowLocalExpressionsCanBeEvaluatedBeforeShardMerge() throws SQLException {
        String columns = "\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(200), \"amount\" DECIMAL(19,4)";
        try (var first = HanaTestDatabase.createTable("SHARD_A", columns); var second = HanaTestDatabase.createTable("SHARD_B", columns);
             var schema = HanaTestDatabase.createSchema("SHARD_JSON"); var ddl = schema.connection().createStatement()) {
            ddl.execute(HanaJsonSupport.createDocumentFunctionSql(schema.name()));
            ShardingDslContract.verify(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(new HanaOptions(5000, schema.name())),
                    HanaExtensions::configure, List.of(first.name(), second.name()));
        }
    }
}
