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

import static org.junit.jupiter.api.Assertions.*;

final class NullIfDslContract {
    private NullIfDslContract() {
    }

    static void verify(DataSource dataSource, DatabaseConfiguration database, Consumer<ServiceCollection> extensions) {
        var query = TestClients.configured(dataSource, database, extensions);
        var client = query.getEasyQueryClient();
        var quote = client.getRuntimeContext().getQueryConfiguration().getDialect();
        var dialect = client.getRuntimeContext().getService(SqlDialect.class);
        String from = dialect == SqlDialect.HANA ? " FROM DUMMY" : "";
        String source = "SELECT 1 AS " + quote.getQuoteName("id") + ",CAST(1.25 AS DECIMAL(19,2)) AS " + quote.getQuoteName("amount")
                + ",'alpha' AS " + quote.getQuoteName("name") + from
                + " UNION ALL SELECT 2,NULL,NULL" + from;
        var rows = query.queryable(source, TestProduct.class).orderBy(p -> p.id().asc());
        assertEquals(Arrays.asList(null, null), rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.amount(), new BigDecimal("1.25"))).toList());
        var amounts = rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.amount(), BigDecimal.ZERO).multiply(100)).toList();
        assertEquals(0, new BigDecimal("125").compareTo(amounts.get(0)));
        assertNull(amounts.get(1));
        var strings = rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.name(), "different").toUpper());
        assertEquals(Arrays.asList("ALPHA", null), strings.toList());
        assertEquals(strings.toList(), strings.cloneQueryable().toList());
        assertEquals(Arrays.asList(null, null), rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIf(p.name(), p.name())).toList());
        assertEquals(Arrays.asList("alpha", null), rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.name(), null)).toList());
        assertEquals(List.of(1, 2), rows.cloneQueryable().where(p -> ProDsl.of(p).nullIfValue(p.name(), "alpha").isNull()).selectColumn(p -> p.id()).toList());
        assertEquals(Arrays.asList(null, false), rows.cloneQueryable().selectColumn(p -> {
            var sql = ProDsl.of(p);
            return sql.nullIfValue(sql.nullSafeEqualsValue(p.id(), 1), true);
        }).toList());

        String timestamp = dialect == SqlDialect.MYSQL ? "DATETIME" : "TIMESTAMP";
        var events = query.queryable("SELECT DATE '2024-03-01' AS " + quote.getQuoteName("event_date")
                + ",CAST('2024-03-01 12:34:56' AS " + timestamp + ") AS " + quote.getQuoteName("occurred") + from, TestEvent.class);
        assertNull(events.cloneQueryable().selectColumn(e -> ProDsl.of(e).nullIfValue(e.eventDate(), LocalDate.of(2024, 3, 1))).toList().get(0));
        assertEquals(LocalDate.of(2024, 3, 1), events.cloneQueryable().selectColumn(e -> ProDsl.of(e).nullIfValue(e.eventDate(), null)).singleNotNull());
        assertNull(events.cloneQueryable().selectColumn(e -> ProDsl.of(e).nullIfValue(e.occurred(), LocalDateTime.of(2024, 3, 1, 12, 34, 56))).toList().get(0));
        assertEquals(2024, events.cloneQueryable().selectColumn(e -> ProDsl.of(e).nullIfValue(e.occurred(), null).year()).singleNotNull());
        var primitives = query.queryable("SELECT 7 AS " + quote.getQuoteName("number") + ",TRUE AS " + quote.getQuoteName("enabled") + from, PrimitiveScalars.class);
        assertNull(primitives.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.number(), 7)).toList().get(0));
        assertNull(primitives.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullIfValue(p.enabled(), true)).toList().get(0));
    }
}
