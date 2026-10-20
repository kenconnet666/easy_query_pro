package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HanaWindowReportIT {
    @Test
    void emptyFramesKeepNullForPrimitiveColumns() throws SQLException {
        WindowReportContract.verifyPrimitiveNulls(TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure));
    }

    @Test
    void windowFramesAndGroupedReports() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("WINDOW_REPORT")) {
            try (var statement = schema.connection().createStatement()) {
                statement.execute("CREATE COLUMN TABLE " + schema.qualified("eqp_report_sale")
                        + " (\"id\" INTEGER PRIMARY KEY, \"region\" NVARCHAR(20), \"report_day\" DATE, \"amount\" DECIMAL(19,4))");
            }
            var query = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            assertEquals(6L, query.insertable(WindowReportContract.sales()).asSchema(ignored -> schema.name()).executeRows());
            WindowReportContract.verify(() -> query.queryable(ReportSale.class).asSchema(ignored -> schema.name()));
        }
    }
}
