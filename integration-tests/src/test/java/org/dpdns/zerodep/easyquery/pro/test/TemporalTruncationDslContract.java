package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.inject.ServiceCollection;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;

import javax.sql.DataSource;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import java.time.LocalDate;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestEventProxy;
import com.easy.query.core.basic.api.select.Query;

import static org.junit.jupiter.api.Assertions.*;

final class TemporalTruncationDslContract {
    private TemporalTruncationDslContract() {
    }

    static void verify(DataSource dataSource, DatabaseConfiguration database, Consumer<ServiceCollection> extensions) {
        DefaultEasyEntityQuery query = TestClients.configured(dataSource, database, extensions);
        QueryRuntimeContext runtime = query.getRuntimeContext();
        SQLKeyword quote = runtime.getQueryConfiguration().getDialect();
        SqlDialect dialect = runtime.getService(SqlDialect.class);
        String timestamp = dialect == SqlDialect.MYSQL ? "DATETIME(6)" : "TIMESTAMP";
        String source = "SELECT CAST(? AS DATE) AS " + quote.getQuoteName("event_date") + ",CAST(? AS " + timestamp + ") AS "
                + quote.getQuoteName("occurred") + (dialect == SqlDialect.HANA ? " FROM DUMMY" : "");
        List<LocalDateTime> inputs = Arrays.asList(LocalDateTime.of(2021, 1, 1, 0, 1, 2, 123456000),
                LocalDateTime.of(2024, 2, 29, 12, 34, 56, 987654000), LocalDateTime.of(2024, 5, 31, 23, 59, 59, 987654000),
                LocalDateTime.of(1582, 10, 18, 12, 34, 56), null);
        for (LocalDateTime input : inputs) {
            LocalDate date = input == null ? null : input.toLocalDate();
            for (TruncationUnit unit : TruncationUnit.values()) {
                LocalDateTime expected = input == null ? null : start(input, unit);
                EntityQueryable<TestEventProxy, TestEvent> rows = query.queryable(source, TestEvent.class, Arrays.asList(date, input));
                List<LocalDateTime> actual = rows.selectColumn(e -> ProDsl.of(e).truncateDateTime(e.occurred(), unit)).toList();
                assertEquals(Arrays.asList(expected), actual, unit + " / " + input);
                if (unit.isDateUnit()) {
                    List<LocalDate> days = query.queryable(source, TestEvent.class, Arrays.asList(date, input))
                            .selectColumn(e -> ProDsl.of(e).truncateDate(e.eventDate(), unit)).toList();
                    assertEquals(Arrays.asList(expected == null ? null : expected.toLocalDate()), days, unit + " / " + date);
                }
            }
        }
        LocalDateTime input = inputs.get(2);
        Query<LocalDateTime> bound = query.queryable(source, TestEvent.class, Arrays.asList(input.toLocalDate(), input))
                .selectColumn(e -> ProDsl.of(e).truncateDateTime(e.expression().constant(input), TruncationUnit.SECOND));
        assertEquals(input.withNano(0), bound.singleNotNull());
        assertEquals(input.withNano(0), bound.cloneQueryable().singleNotNull());
        EntityQueryable<TestEventProxy, TestEvent> filtered = query.queryable(source, TestEvent.class, Arrays.asList(input.toLocalDate(), input))
                .where(e -> ProDsl.of(e).truncateDateTime(e.occurred(), TruncationUnit.MONTH).eq(start(input, TruncationUnit.MONTH)));
        assertEquals(1, filtered.toList().size());
    }

    private static LocalDateTime start(LocalDateTime input, TruncationUnit unit) {
        switch (unit) {
            case YEAR: return input.toLocalDate().withDayOfYear(1).atStartOfDay();
            case QUARTER: return input.toLocalDate().withDayOfMonth(1).withMonth((input.getMonthValue() - 1) / 3 * 3 + 1).atStartOfDay();
            case MONTH: return input.toLocalDate().withDayOfMonth(1).atStartOfDay();
            case ISO_WEEK: return input.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case DAY: return input.toLocalDate().atStartOfDay();
            case HOUR: return input.withMinute(0).withSecond(0).withNano(0);
            case MINUTE: return input.withSecond(0).withNano(0);
            case SECOND: return input.withNano(0);
        }
        throw new IllegalStateException("Unhandled value: " + unit);
    }
}
