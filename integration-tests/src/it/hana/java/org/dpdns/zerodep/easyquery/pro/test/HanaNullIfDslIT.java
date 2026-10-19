package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class HanaNullIfDslIT {
    @Test
    void nullIfKeepsScalarTypesAndSqlNulls() throws SQLException {
        NullIfDslContract.verify(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
    }
}
