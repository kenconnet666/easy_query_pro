package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.core.inject.ServiceCollection;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MySqlQueryContextIT {
    @Test
    void boundUnionAndCteRetainOrderingCountAndPaging() throws SQLException {
        var query = TestClients.configured(MySqlTestDatabase.dataSource(), new MySQLDatabaseConfiguration(), MySqlQueryContextIT::configure);
        var first = query.queryable("SELECT CAST(? AS SIGNED) AS id", TestProduct.class, List.of(1)).select(p -> Select.DRAFT.of(p.id()));
        var second = query.queryable("SELECT CAST(? AS SIGNED) AS id", TestProduct.class, List.of(2)).select(p -> Select.DRAFT.of(p.id()));
        var rows = first.unionAll(second).toCteAs("context_union").where(p -> p.value1().ge(1)).orderBy(p -> p.value1().asc());
        assertEquals(rows.toSQL(), rows.cloneQueryable().toSQL());
        assertEquals(List.of(1, 2), rows.cloneQueryable().toList().stream().map(row -> row.getValue1()).toList());
        assertEquals(2L, rows.cloneQueryable().count());
        var page = rows.toPageResult(2, 1);
        assertEquals(2L, page.getTotal());
        assertEquals(2, page.getData().get(0).getValue1());
    }

    @Test
    void existingScalarGroupingAndCalendarContractsAlsoUseTheScopedFactory() throws SQLException {
        var source = MySqlTestDatabase.dataSource();
        DecimalDslContract.verify(source, new MySQLDatabaseConfiguration(), MySqlQueryContextIT::configure);
        var query = TestClients.configured(source, new MySQLDatabaseConfiguration(), MySqlQueryContextIT::configure);
        GroupingReportContract.verify(query);
        CalendarReportContract.verify(query);
    }

    private static void configure(ServiceCollection services) {
        MySqlExtensions.configure(services);
        MySqlExtensions.configureQueryScope(services);
    }
}
