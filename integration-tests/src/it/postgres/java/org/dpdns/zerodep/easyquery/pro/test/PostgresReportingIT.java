package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportSaleProxy;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresReportingIT {
    @Test
    void emptyFramesKeepNullForPrimitiveColumns() throws SQLException {
        WindowReportContract.verifyPrimitiveNulls(TestClients.configured(PostgresTestDatabase.dataSource(), new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure));
    }

    @Test
    void windowFramesAndGroupedReports() throws SQLException {
        withSales(rows -> WindowReportContract.verify(rows::cloneQueryable));
    }

    private void withSales(Consumer<EntityQueryable<ReportSaleProxy, ReportSale>> contract) throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        try (var schema = PostgresTestDatabase.createSchema(source, "eqp_window_")) {
            try (var statement = schema.connection().createStatement()) {
                statement.execute("CREATE TABLE " + schema.qualified("eqp_report_sale") + " (id INTEGER PRIMARY KEY, region VARCHAR(20), report_day DATE, amount DECIMAL(19,4))");
            }
            var query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
            assertEquals(6L, query.insertable(WindowReportContract.sales()).executeRows());
            contract.accept(query.queryable(ReportSale.class));
        }
    }
}
