package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class HanaTextDslIT {
    @Test
    void boundedUnicodeTextRetainsValuesAndNativeCastRules() throws SQLException {
        TextDslContract.verify(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
    }
}
