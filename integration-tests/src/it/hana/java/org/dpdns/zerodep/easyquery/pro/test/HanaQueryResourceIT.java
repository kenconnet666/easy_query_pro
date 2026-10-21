package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import java.sql.Statement;

class HanaQueryResourceIT {
    @Test
    void streamingFailuresAndConcurrentReadersReleaseTheirOwnResources() throws Exception {
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("QUERY_RESOURCES")) {
            try (Statement statement = schema.connection().createStatement()) {
                statement.execute("CREATE COLUMN TABLE " + schema.qualified("eqp_product")
                        + " (\"id\" INTEGER PRIMARY KEY,\"name\" NVARCHAR(100),\"amount\" DECIMAL(19,4))");
            }
            QueryResourceContract.seed(schema.connection(), schema.qualified("eqp_product"));
            QueryResourceContract.verify(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure,
                    "eqp_product", schema.name());
        }
    }
}
