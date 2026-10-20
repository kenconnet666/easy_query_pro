package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.window.MySqlRangeInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RangeBoundary;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RowBoundary;
import org.dpdns.zerodep.easyquery.pro.dsl.window.WindowSpec;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RangeWindowContract {
    private RangeWindowContract() {
    }

    static void verify(DefaultEasyEntityQuery query) {
        verifyNumericPeers(query);
        verifyDecimalDistance(query);
        verifyZeroAndEmptyFrames(query);
        verifyNullKeys(query);
        verifyCalendarDistance(query);
        verifyMicrosecondDistance(query);
    }

    static WindowSpec<?> numeric(ProxyEntity<?, ?> proxy, PropTypeColumn<?> key, RangeBoundary<? extends Number> start,
                                 RangeBoundary<? extends Number> end, boolean ascending) {
        return proxy.getEntitySQLContext().getRuntimeContext().getService(SqlDialect.class) == SqlDialect.MYSQL
                ? MySqlDsl.of(proxy).nativeWindow().orderBy(key, ascending).rangeNumericBetween(start, end)
                : PgSqlDsl.of(proxy).nativeWindow().orderBy(key, ascending).rangeNumericBetween(start, end);
    }

    static WindowSpec<?> temporal(ProxyEntity<?, ?> proxy, PropTypeColumn<?> key, int amount, ChronoUnit unit) {
        return temporal(proxy, key, amount, unit, true);
    }

    static WindowSpec<?> temporal(ProxyEntity<?, ?> proxy, PropTypeColumn<?> key, int amount, ChronoUnit unit, boolean ascending) {
        if (proxy.getEntitySQLContext().getRuntimeContext().getService(SqlDialect.class) == SqlDialect.MYSQL)
            return MySqlDsl.of(proxy).nativeWindow().orderBy(key, ascending).rangeIntervalBetween(
                    RangeBoundary.preceding(new MySqlRangeInterval(amount, unit)), RangeBoundary.currentRow());
        var interval = switch (unit) {
            case MONTHS -> PgInterval.of(amount, 0, 0);
            case DAYS -> PgInterval.of(0, amount, 0);
            case MICROS -> PgInterval.of(0, 0, amount);
            default -> throw new IllegalArgumentException("Unsupported test interval unit");
        };
        return PgSqlDsl.of(proxy).nativeWindow().orderBy(key, ascending).rangeIntervalBetween(RangeBoundary.preceding(interval), RangeBoundary.currentRow());
    }

    private static String sales(DefaultEasyEntityQuery query) {
        var quote = query.getRuntimeContext().getQueryConfiguration().getDialect();
        return "SELECT 1 AS " + quote.getQuoteName("id") + ",'A' AS " + quote.getQuoteName("region")
                + ",DATE '2024-01-01' AS " + quote.getQuoteName("report_day") + ",CAST(10 AS DECIMAL(19,4)) AS " + quote.getQuoteName("amount")
                + " UNION ALL SELECT 1,'A',DATE '2024-01-01',20"
                + " UNION ALL SELECT 2,'A',DATE '2024-01-02',5"
                + " UNION ALL SELECT 4,'A',DATE '2024-01-10',7";
    }

    private static void verifyNumericPeers(DefaultEasyEntityQuery query) {
        var selected = query.queryable(sales(query), ReportSale.class).select(p -> {
            var pro = ProDsl.of(p);
            return Select.DRAFT.of(p.id(), p.amount(),
                    pro.over(p.amount().sumBigDecimal(), numeric(p, p.id(), RangeBoundary.preceding(1), RangeBoundary.currentRow(), true)),
                    pro.over(p.amount().sumBigDecimal(), numeric(p, p.id(), RangeBoundary.preceding(1), RangeBoundary.currentRow(), false)));
        }).orderBy(p -> {
            p.value1().asc();
            p.value2().asc();
        });
        var result = selected.cloneQueryable().toList();
        decimals(List.of("30", "30", "35", "7"), result.stream().map(r -> r.getValue3()).toList());
        decimals(List.of("35", "35", "5", "7"), result.stream().map(r -> r.getValue4()).toList());
        assertEquals(4L, selected.cloneQueryable().count());
        assertEquals(4L, selected.cloneQueryable().toCteAs("eqp_range_report").toPageResult(1, 2).getTotal());
        var copy = selected.cloneQueryable().toSQLResult();
        var original = selected.toSQLResult();
        assertEquals(original.getSQL(), copy.getSQL());
        assertEquals(original.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList(),
                copy.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
    }

    private static void verifyDecimalDistance(DefaultEasyEntityQuery query) {
        var quote = query.getRuntimeContext().getQueryConfiguration().getDialect();
        String source = "SELECT 1 AS " + quote.getQuoteName("id") + ",CAST(1.0 AS DECIMAL(19,4)) AS " + quote.getQuoteName("amount")
                + " UNION ALL SELECT 2,1.1 UNION ALL SELECT 3,1.2";
        var result = query.queryable(source, ReportSale.class).select(p -> Select.DRAFT.of(p.id(), ProDsl.of(p).over(p.id().sumLong(),
                        numeric(p, p.amount(), RangeBoundary.preceding(new BigDecimal("0.1")), RangeBoundary.currentRow(), true))))
                .orderBy(p -> p.value1().asc()).toList();
        assertEquals(List.of(1L, 3L, 5L), result.stream().map(r -> r.getValue2()).toList());
    }

    private static void verifyZeroAndEmptyFrames(DefaultEasyEntityQuery query) {
        var selected = query.queryable(sales(query), ReportSale.class).select(p -> {
            var pro = ProDsl.of(p);
            var empty = numeric(p, p.id(), RangeBoundary.preceding(1), RangeBoundary.preceding(2), true);
            var zero = numeric(p, p.id(), RangeBoundary.following(0), RangeBoundary.following(0), true);
            var reversed = numeric(p, p.id(), RangeBoundary.following(1), RangeBoundary.following(0), true);
            return Select.DRAFT.of(p.id(), p.amount(), pro.over(p.amount().count(), empty), pro.over(p.amount().sumBigDecimal(), empty),
                    pro.over(p.amount().sumBigDecimal(), zero), pro.over(p.amount().count(), reversed));
        }).orderBy(p -> {
            p.value1().asc();
            p.value2().asc();
        });
        var result = selected.toList();
        assertTrue(result.stream().allMatch(r -> r.getValue3() == 0 && r.getValue4() == null && r.getValue6() == 0));
        decimals(List.of("30", "30", "5", "7"), result.stream().map(r -> r.getValue5()).toList());
    }

    private static void verifyNullKeys(DefaultEasyEntityQuery query) {
        var result = query.queryable(sales(query) + " UNION ALL SELECT NULL,'A',DATE '2024-01-03',11", ReportSale.class)
                .select(p -> Select.DRAFT.of(p.amount(), ProDsl.of(p).over(p.amount().sumBigDecimal(),
                        numeric(p, p.id(), RangeBoundary.preceding(1), RangeBoundary.currentRow(), true))))
                .orderBy(p -> p.value1().asc()).toList();
        decimals(List.of("35", "7", "30", "11", "30"), result.stream().map(r -> r.getValue2()).toList());
    }

    private static void verifyCalendarDistance(DefaultEasyEntityQuery query) {
        var result = query.queryable(sales(query), ReportSale.class).select(p -> {
            var pro = ProDsl.of(p);
            return Select.DRAFT.of(p.day(), p.amount(), pro.over(p.amount().sumBigDecimal(), temporal(p, p.day(), 2, ChronoUnit.DAYS)),
                    pro.over(p.amount().sumBigDecimal(), pro.window().orderBy(p.day(), p.amount()).rowsBetween(RowBoundary.preceding(2), RowBoundary.currentRow())),
                    pro.over(p.amount().sumBigDecimal(), temporal(p, p.day(), 2, ChronoUnit.DAYS, false)));
        }).orderBy(p -> {
            p.value1().asc();
            p.value2().asc();
        }).toList();
        decimals(List.of("30", "30", "35", "7"), result.stream().map(r -> r.getValue3()).toList());
        decimals(List.of("10", "30", "35", "32"), result.stream().map(r -> r.getValue4()).toList());
        decimals(List.of("35", "35", "5", "7"), result.stream().map(r -> r.getValue5()).toList());
        var quote = query.getRuntimeContext().getQueryConfiguration().getDialect();
        String months = "SELECT 1 AS " + quote.getQuoteName("id") + ",DATE '2024-01-31' AS " + quote.getQuoteName("report_day")
                + ",CAST(1 AS DECIMAL(19,4)) AS " + quote.getQuoteName("amount")
                + " UNION ALL SELECT 2,DATE '2024-02-28',2 UNION ALL SELECT 3,DATE '2024-02-29',4 UNION ALL SELECT 4,DATE '2024-03-31',8";
        var calendar = query.queryable(months, ReportSale.class).select(p -> Select.DRAFT.of(p.id(),
                        ProDsl.of(p).over(p.amount().sumBigDecimal(), temporal(p, p.day(), 1, ChronoUnit.MONTHS))))
                .orderBy(p -> p.value1().asc()).toList();
        decimals(List.of("1", "3", "7", "12"), calendar.stream().map(r -> r.getValue2()).toList());
    }

    private static void verifyMicrosecondDistance(DefaultEasyEntityQuery query) {
        var quote = query.getRuntimeContext().getQueryConfiguration().getDialect();
        String timestamp = query.getRuntimeContext().getService(SqlDialect.class) == SqlDialect.MYSQL ? "DATETIME(6)" : "TIMESTAMP(6)";
        String source = "SELECT CAST('2024-01-01 12:00:00.000000' AS " + timestamp + ") AS " + quote.getQuoteName("occurred")
                + ",1 AS " + quote.getQuoteName("delta")
                + " UNION ALL SELECT CAST('2024-01-01 12:00:00.000001' AS " + timestamp + "),2"
                + " UNION ALL SELECT CAST('2024-01-01 12:00:00.000003' AS " + timestamp + "),3";
        var result = query.queryable(source, TestEvent.class).select(p -> Select.DRAFT.of(p.delta(),
                        ProDsl.of(p).over(p.delta().sumLong(), temporal(p, p.occurred(), 1, ChronoUnit.MICROS))))
                .orderBy(p -> p.value1().asc()).toList();
        assertEquals(List.of(1L, 3L, 3L), result.stream().map(r -> r.getValue2()).toList());
    }

    private static void decimals(List<String> expected, List<BigDecimal> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertNotNull(actual.get(i));
            assertEquals(0, new BigDecimal(expected.get(i)).compareTo(actual.get(i)), "row " + i + ": " + actual.get(i));
        }
    }
}
