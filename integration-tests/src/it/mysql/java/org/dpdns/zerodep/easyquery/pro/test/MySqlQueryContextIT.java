package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.core.inject.ServiceCollection;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft1Proxy;
import com.easy.query.core.proxy.core.draft.Draft1;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.easy.query.core.api.pagination.EasyPageResult;
import com.mysql.cj.jdbc.MysqlDataSource;

import static org.junit.jupiter.api.Assertions.*;

class MySqlQueryContextIT {
    @Test
    void boundUnionAndCteRetainOrderingCountAndPaging() throws SQLException {
        DefaultEasyEntityQuery query = TestClients.configured(MySqlTestDatabase.dataSource(), new MySQLDatabaseConfiguration(), MySqlQueryContextIT::configure);
        EntityQueryable<Draft1Proxy<Integer>, Draft1<Integer>> first = query.queryable("SELECT CAST(? AS SIGNED) AS id", TestProduct.class, Arrays.asList(1)).select(p -> Select.DRAFT.of(p.id()));
        EntityQueryable<Draft1Proxy<Integer>, Draft1<Integer>> second = query.queryable("SELECT CAST(? AS SIGNED) AS id", TestProduct.class, Arrays.asList(2)).select(p -> Select.DRAFT.of(p.id()));
        EntityQueryable<Draft1Proxy<Integer>, Draft1<Integer>> rows = first.unionAll(second).toCteAs("context_union").where(p -> p.value1().ge(1)).orderBy(p -> p.value1().asc());
        assertEquals(rows.toSQL(), rows.cloneQueryable().toSQL());
        assertEquals(Arrays.asList(1, 2), rows.cloneQueryable().toList().stream().map(row -> row.getValue1()).collect(Collectors.toList()));
        assertEquals(2L, rows.cloneQueryable().count());
        EasyPageResult<Draft1<Integer>> page = rows.toPageResult(2, 1);
        assertEquals(2L, page.getTotal());
        assertEquals(2, page.getData().get(0).getValue1());
    }

    @Test
    void existingScalarGroupingAndCalendarContractsAlsoUseTheScopedFactory() throws SQLException {
        MysqlDataSource source = MySqlTestDatabase.dataSource();
        DecimalDslContract.verify(source, new MySQLDatabaseConfiguration(), MySqlQueryContextIT::configure);
        DefaultEasyEntityQuery query = TestClients.configured(source, new MySQLDatabaseConfiguration(), MySqlQueryContextIT::configure);
        GroupingReportContract.verify(query);
        CalendarReportContract.verify(query);
    }

    private static void configure(ServiceCollection services) {
        MySqlExtensions.configure(services);
        MySqlExtensions.configureQueryScope(services);
    }
}
