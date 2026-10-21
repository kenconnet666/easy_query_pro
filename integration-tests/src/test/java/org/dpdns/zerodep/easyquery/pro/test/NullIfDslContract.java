package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.inject.ServiceCollection;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.PrimitiveScalars;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import com.easy.query.core.basic.api.select.Query;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestEventProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PrimitiveScalarsProxy;

import static org.junit.jupiter.api.Assertions.*;

final class NullIfDslContract {
    private NullIfDslContract() {
    }

    static void verify(DataSource dataSource, DatabaseConfiguration database, Consumer<ServiceCollection> extensions) {
        DefaultEasyEntityQuery query = TestClients.configured(dataSource, database, extensions);
        EasyQueryClient client = query.getEasyQueryClient();
        SQLKeyword quote = client.getRuntimeContext().getQueryConfiguration().getDialect();
        SqlDialect dialect = client.getRuntimeContext().getService(SqlDialect.class);
        String from = dialect == SqlDialect.HANA ? " FROM DUMMY" : "";
        String source = "SELECT 1 AS " + quote.getQuoteName("id") + ",CAST(1.25 AS DECIMAL(19,2)) AS " + quote.getQuoteName("amount")
                + ",'alpha' AS " + quote.getQuoteName("name") + from
                + " UNION ALL SELECT 2,NULL,NULL" + from;
        EntityQueryable<TestProductProxy, TestProduct> rows = query.queryable(source, TestProduct.class).orderBy(p -> p.id().asc());
        assertEquals(Arrays.asList(null, null), rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.amount(), new BigDecimal("1.25"))).toList());
        List<BigDecimal> amounts = rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.amount(), BigDecimal.ZERO).multiply(100)).toList();
        assertEquals(0, new BigDecimal("125").compareTo(amounts.get(0)));
        assertNull(amounts.get(1));
        Query<String> strings = rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.name(), "different").toUpper());
        assertEquals(Arrays.asList("ALPHA", null), strings.toList());
        assertEquals(strings.toList(), strings.cloneQueryable().toList());
        assertEquals(Arrays.asList(null, null), rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIf(p.name(), p.name())).toList());
        assertEquals(Arrays.asList("alpha", null), rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.name(), null)).toList());
        assertEquals(Arrays.asList(1, 2), rows.cloneQueryable().where(p -> ProDsl.of(p).nullIfValue(p.name(), "alpha").isNull()).selectColumn(p -> p.id()).toList());
        assertEquals(Arrays.asList(null, false), rows.cloneQueryable().selectColumn(p -> {
            ProDsl sql = ProDsl.of(p);
            return sql.nullIfValue(sql.nullSafeEqualsValue(p.id(), 1), true);
        }).toList());

        String timestamp = dialect == SqlDialect.MYSQL ? "DATETIME" : "TIMESTAMP";
        EntityQueryable<TestEventProxy, TestEvent> events = query.queryable("SELECT DATE '2024-03-01' AS " + quote.getQuoteName("event_date")
                + ",CAST('2024-03-01 12:34:56' AS " + timestamp + ") AS " + quote.getQuoteName("occurred") + from, TestEvent.class);
        assertNull(events.cloneQueryable().selectColumn(e -> ProDsl.of(e).nullIfValue(e.eventDate(), LocalDate.of(2024, 3, 1))).toList().get(0));
        assertEquals(LocalDate.of(2024, 3, 1), events.cloneQueryable().selectColumn(e -> ProDsl.of(e).nullIfValue(e.eventDate(), null)).singleNotNull());
        assertNull(events.cloneQueryable().selectColumn(e -> ProDsl.of(e).nullIfValue(e.occurred(), LocalDateTime.of(2024, 3, 1, 12, 34, 56))).toList().get(0));
        assertEquals(2024, events.cloneQueryable().selectColumn(e -> ProDsl.of(e).nullIfValue(e.occurred(), null).year()).singleNotNull());
        EntityQueryable<PrimitiveScalarsProxy, PrimitiveScalars> primitives = query.queryable("SELECT 7 AS " + quote.getQuoteName("number") + ",TRUE AS " + quote.getQuoteName("enabled") + from, PrimitiveScalars.class);
        assertNull(primitives.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.number(), 7)).toList().get(0));
        assertNull(primitives.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.enabled(), true)).toList().get(0));
    }
}
