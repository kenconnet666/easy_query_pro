package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportSaleProxy;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.Statement;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresReportingIT {
    @Test
    void globalRankingPreservesPeersAndGroupedReportBoundaries() throws SQLException {
        OrderReportContract.verifyRankingWindows(TestClients.configured(PostgresTestDatabase.dataSource(), new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure));
    }

    @Test
    void conditionalOrderMetricsAndTopRanks() throws SQLException {
        OrderReportContract.verify(TestClients.configured(PostgresTestDatabase.dataSource(), new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure));
    }

    @Test
    void yearAndMonthComparisonsUseCalendarOffsets() throws SQLException {
        CalendarReportContract.verifyYearOverYear(TestClients.configured(PostgresTestDatabase.dataSource(), new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure));
    }

    @Test
    void calendarMonthsKeepEmptyBucketsBeforeLagAndPaging() throws SQLException {
        CalendarReportContract.verify(TestClients.configured(PostgresTestDatabase.dataSource(), new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure));
    }

    @Test
    void groupingReportsKeepSubtotalNullsAndCounts() throws SQLException {
        GroupingReportContract.verify(TestClients.configured(PostgresTestDatabase.dataSource(), new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure));
    }

    @Test
    void emptyFramesKeepNullForPrimitiveColumns() throws SQLException {
        WindowReportContract.verifyPrimitiveNulls(TestClients.configured(PostgresTestDatabase.dataSource(), new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure));
    }

    @Test
    void windowFramesAndGroupedReports() throws SQLException {
        withSales((rows, ignored) -> WindowReportContract.verify(rows::cloneQueryable));
    }

    private void withSales(BiConsumer<EntityQueryable<ReportSaleProxy, ReportSale>, DataSource> contract) throws SQLException {
        PGSimpleDataSource source = PostgresTestDatabase.dataSource();
        try (PostgresTestDatabase.Schema schema = PostgresTestDatabase.createSchema(source, "eqp_window_")) {
            try (Statement statement = schema.connection().createStatement()) {
                statement.execute("CREATE TABLE " + schema.qualified("eqp_report_sale") + " (id INTEGER PRIMARY KEY, region VARCHAR(20), report_day DATE, amount DECIMAL(19,4))");
            }
            DefaultEasyEntityQuery query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
            assertEquals(6L, query.insertable(WindowReportContract.sales()).executeRows());
            contract.accept(query.queryable(ReportSale.class), source);
        }
    }

    @Test
    void populationAndSampleStatisticsComposeWithReports() throws SQLException {
        withSales((rows, source) -> StatisticsContract.verify(rows::cloneQueryable, source));
    }
}
