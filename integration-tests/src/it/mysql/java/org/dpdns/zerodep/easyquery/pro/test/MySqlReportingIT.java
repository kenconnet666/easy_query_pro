package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportSaleProxy;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.function.BiConsumer;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MySqlReportingIT {
    @Test
    void globalRankingPreservesPeersAndGroupedReportBoundaries() throws SQLException {
        OrderReportContract.verifyRankingWindows(TestClients.configured(MySqlTestDatabase.dataSource(), new MySQLDatabaseConfiguration(), MySqlExtensions::configure));
    }

    @Test
    void conditionalOrderMetricsAndTopRanks() throws SQLException {
        OrderReportContract.verify(TestClients.configured(MySqlTestDatabase.dataSource(), new MySQLDatabaseConfiguration(), MySqlExtensions::configure));
    }

    @Test
    void yearAndMonthComparisonsUseCalendarOffsets() throws SQLException {
        CalendarReportContract.verifyYearOverYear(TestClients.configured(MySqlTestDatabase.dataSource(), new MySQLDatabaseConfiguration(), MySqlExtensions::configure));
    }

    @Test
    void calendarMonthsKeepEmptyBucketsBeforeLagAndPaging() throws SQLException {
        CalendarReportContract.verify(TestClients.configured(MySqlTestDatabase.dataSource(), new MySQLDatabaseConfiguration(), MySqlExtensions::configure));
    }

    @Test
    void groupingReportsKeepSubtotalNullsAndCounts() throws SQLException {
        GroupingReportContract.verify(TestClients.configured(MySqlTestDatabase.dataSource(), new MySQLDatabaseConfiguration(), MySqlExtensions::configure));
    }

    @Test
    void emptyFramesKeepNullForPrimitiveColumns() throws SQLException {
        WindowReportContract.verifyPrimitiveNulls(TestClients.configured(MySqlTestDatabase.dataSource(), new MySQLDatabaseConfiguration(), MySqlExtensions::configure));
    }

    @Test
    void windowFramesAndGroupedReports() throws SQLException {
        withSales((rows, ignored) -> WindowReportContract.verify(rows::cloneQueryable));
    }

    private void withSales(BiConsumer<EntityQueryable<ReportSaleProxy, ReportSale>, DataSource> contract) throws SQLException {
        var source = MySqlTestDatabase.dataSource();
        try (var table = MySqlTestDatabase.createTable(source, "eqp_window_", "id INTEGER PRIMARY KEY, region VARCHAR(20), report_day DATE, amount DECIMAL(19,4)")) {
            var query = TestClients.configured(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
            assertEquals(6L, query.insertable(WindowReportContract.sales()).asTable(table.name()).executeRows());
            contract.accept(query.queryable(ReportSale.class).asTable(table.name()), source);
        }
    }

    @Test
    void populationAndSampleStatisticsComposeWithReports() throws SQLException {
        withSales((rows, source) -> StatisticsContract.verify(rows::cloneQueryable, source));
    }
}
