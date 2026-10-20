package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MySqlWindowReportIT {
    @Test
    void emptyFramesKeepNullForPrimitiveColumns() throws SQLException {
        WindowReportContract.verifyPrimitiveNulls(TestClients.configured(MySqlTestDatabase.dataSource(), new MySQLDatabaseConfiguration(), MySqlExtensions::configure));
    }

    @Test
    void windowFramesAndGroupedReports() throws SQLException {
        var source = MySqlTestDatabase.dataSource();
        try (var table = MySqlTestDatabase.createTable(source, "eqp_window_", "id INTEGER PRIMARY KEY, region VARCHAR(20), report_day DATE, amount DECIMAL(19,4)")) {
            var query = TestClients.configured(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
            assertEquals(6L, query.insertable(WindowReportContract.sales()).asTable(table.name()).executeRows());
            WindowReportContract.verify(() -> query.queryable(ReportSale.class).asTable(table.name()));
        }
    }
}
