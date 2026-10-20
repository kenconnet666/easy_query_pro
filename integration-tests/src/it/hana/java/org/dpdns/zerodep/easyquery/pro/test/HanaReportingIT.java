package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportSaleProxy;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.function.BiConsumer;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HanaReportingIT {
    @Test
    void calendarMonthsKeepEmptyBucketsBeforeLagAndPaging() throws SQLException {
        CalendarReportContract.verify(TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure));
    }

    @Test
    void groupingReportsKeepSubtotalNullsAndCounts() throws SQLException {
        GroupingReportContract.verify(TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure));
    }

    @Test
    void emptyFramesKeepNullForPrimitiveColumns() throws SQLException {
        WindowReportContract.verifyPrimitiveNulls(TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure));
    }

    @Test
    void windowFramesAndGroupedReports() throws SQLException {
        withSales((rows, ignored) -> WindowReportContract.verify(rows::cloneQueryable));
    }

    private void withSales(BiConsumer<EntityQueryable<ReportSaleProxy, ReportSale>, DataSource> contract) throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("WINDOW_REPORT")) {
            try (var statement = schema.connection().createStatement()) {
                statement.execute("CREATE COLUMN TABLE " + schema.qualified("eqp_report_sale")
                        + " (\"id\" INTEGER PRIMARY KEY, \"region\" NVARCHAR(20), \"report_day\" DATE, \"amount\" DECIMAL(19,4))");
            }
            var source = HanaTestDatabase.dataSource();
            var query = TestClients.configured(source, new HanaDatabaseConfiguration(), HanaExtensions::configure);
            assertEquals(6L, query.insertable(WindowReportContract.sales()).asSchema(ignored -> schema.name()).executeRows());
            contract.accept(query.queryable(ReportSale.class).asSchema(ignored -> schema.name()), source);
        }
    }

    @Test
    void populationAndSampleStatisticsComposeWithReports() throws SQLException {
        withSales((rows, source) -> StatisticsContract.verify(rows::cloneQueryable, source));
    }
}
