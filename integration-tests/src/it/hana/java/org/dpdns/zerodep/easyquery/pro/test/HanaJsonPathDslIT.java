package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.hana.json.HanaJsonSupport;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Connection;
import java.sql.Statement;

class HanaJsonPathDslIT {
    @Test
    void literalPathsDistinguishMissingNullAndContainerKinds() throws SQLException {
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("JSON_PATH");
             Connection connection = HanaTestDatabase.connect(); Statement statement = connection.createStatement()) {
            statement.execute(HanaJsonSupport.createDocumentFunctionSql(schema.name()));
            JsonPathDslContract.verify(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(new HanaOptions(5000, schema.name())), HanaExtensions::configure);
        }
    }
}
