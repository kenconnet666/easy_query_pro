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

import static org.junit.jupiter.api.Assertions.*;

final class TemporalTruncationDslContract {
    private TemporalTruncationDslContract() {
    }

    static void verify(DataSource dataSource, DatabaseConfiguration database, Consumer<ServiceCollection> extensions) {
        var query = TestClients.configured(dataSource, database, extensions);
        var runtime = query.getRuntimeContext();
        var quote = runtime.getQueryConfiguration().getDialect();
        var dialect = runtime.getService(SqlDialect.class);
        String timestamp = dialect == SqlDialect.MYSQL ? "DATETIME(6)" : "TIMESTAMP";
        String source = "SELECT CAST(? AS DATE) AS " + quote.getQuoteName("event_date") + ",CAST(? AS " + timestamp + ") AS "
                + quote.getQuoteName("occurred") + (dialect == SqlDialect.HANA ? " FROM DUMMY" : "");
        var inputs = Arrays.asList(LocalDateTime.of(2021, 1, 1, 0, 1, 2, 123456000),
                LocalDateTime.of(2024, 2, 29, 12, 34, 56, 987654000), LocalDateTime.of(2024, 5, 31, 23, 59, 59, 987654000),
                LocalDateTime.of(1582, 10, 18, 12, 34, 56), null);
        for (var input : inputs) {
            var date = input == null ? null : input.toLocalDate();
            for (var unit : TruncationUnit.values()) {
                var expected = input == null ? null : start(input, unit);
                var rows = query.queryable(source, TestEvent.class, Arrays.asList(date, input));
                var actual = rows.selectColumn(e -> ProDsl.of(e).truncateDateTime(e.occurred(), unit)).toList();
                assertEquals(Arrays.asList(expected), actual, unit + " / " + input);
                if (unit.isDateUnit()) {
                    var days = query.queryable(source, TestEvent.class, Arrays.asList(date, input))
                            .selectColumn(e -> ProDsl.of(e).truncateDate(e.eventDate(), unit)).toList();
                    assertEquals(Arrays.asList(expected == null ? null : expected.toLocalDate()), days, unit + " / " + date);
                }
            }
        }
        var input = inputs.get(2);
        var bound = query.queryable(source, TestEvent.class, List.of(input.toLocalDate(), input))
                .selectColumn(e -> ProDsl.of(e).truncateDateTime(e.expression().constant(input), TruncationUnit.SECOND));
        assertEquals(input.withNano(0), bound.singleNotNull());
        assertEquals(input.withNano(0), bound.cloneQueryable().singleNotNull());
        var filtered = query.queryable(source, TestEvent.class, List.of(input.toLocalDate(), input))
                .where(e -> ProDsl.of(e).truncateDateTime(e.occurred(), TruncationUnit.MONTH).eq(start(input, TruncationUnit.MONTH)));
        assertEquals(1, filtered.toList().size());
    }

    private static LocalDateTime start(LocalDateTime input, TruncationUnit unit) {
        return switch (unit) {
            case YEAR -> input.toLocalDate().withDayOfYear(1).atStartOfDay();
            case QUARTER ->
                    input.toLocalDate().withDayOfMonth(1).withMonth((input.getMonthValue() - 1) / 3 * 3 + 1).atStartOfDay();
            case MONTH -> input.toLocalDate().withDayOfMonth(1).atStartOfDay();
            case ISO_WEEK ->
                    input.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case DAY -> input.toLocalDate().atStartOfDay();
            case HOUR -> input.withMinute(0).withSecond(0).withNano(0);
            case MINUTE -> input.withSecond(0).withNano(0);
            case SECOND -> input.withNano(0);
        };
    }
}
