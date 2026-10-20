package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RowBoundary;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;
import org.dpdns.zerodep.easyquery.pro.test.entity.PrimitiveScalars;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportSaleProxy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

final class WindowReportContract {
    private WindowReportContract() {
    }

    static List<ReportSale> sales() {
        return List.of(new ReportSale(1, "A", LocalDate.of(2024, 1, 1), "10"),
                new ReportSale(2, "A", LocalDate.of(2024, 1, 1), "20"),
                new ReportSale(3, "A", LocalDate.of(2024, 1, 2), "5"),
                new ReportSale(4, "B", LocalDate.of(2024, 1, 1), "7"),
                new ReportSale(5, "B", LocalDate.of(2024, 1, 3), "3"),
                new ReportSale(6, "C", LocalDate.of(2024, 1, 1), null));
    }

    static void verify(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows) {
        verifyFrames(rows);
        verifyGroupedReport(rows);
        verifyDistinctBoundary(rows);
    }

    private static void verifyFrames(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows) {
        var selected = rows.get().select(p -> {
            var pro = ProDsl.of(p);
            var ordered = pro.window().partitionBy(p.region()).orderBy(p.id());
            var entire = ordered.rowsBetween(RowBoundary.unboundedPreceding(), RowBoundary.unboundedFollowing());
            var next = ordered.rowsBetween(RowBoundary.following(1), RowBoundary.following(2));
            return Select.DRAFT.of(p.id(), pro.overValue(p.amount(), entire).firstValue(),
                    pro.overValue(p.amount(), entire).lastValue(), pro.overValue(p.amount(), entire).nthValue(2),
                    pro.overValue(p.amount(), ordered).lastValue(), pro.over(p.amount().count(), next),
                    pro.over(p.amount().sumBigDecimal(), next));
        }).orderBy(p -> p.value1().asc());
        var result = selected.cloneQueryable().toList();
        assertDecimals(Arrays.asList("10", "10", "10", "7", "7", null), result.stream().map(r -> r.getValue2()).toList());
        assertDecimals(Arrays.asList("5", "5", "5", "3", "3", null), result.stream().map(r -> r.getValue3()).toList());
        assertDecimals(Arrays.asList("20", "20", "20", "3", "3", null), result.stream().map(r -> r.getValue4()).toList());
        assertDecimals(Arrays.asList("10", "20", "5", "7", "3", null), result.stream().map(r -> r.getValue5()).toList());
        assertEquals(List.of(2L, 1L, 0L, 1L, 0L, 0L), result.stream().map(r -> r.getValue6()).toList());
        assertDecimals(Arrays.asList("25", "5", null, "3", null, null), result.stream().map(r -> r.getValue7()).toList());
        assertEquals(6L, selected.cloneQueryable().count());
    }

    static void verifyPrimitiveNulls(DefaultEasyEntityQuery query) {
        var runtime = query.getEasyQueryClient().getRuntimeContext();
        var quote = runtime.getQueryConfiguration().getDialect();
        String from = runtime.getService(SqlDialect.class) == SqlDialect.HANA ? " FROM DUMMY" : "";
        // A constant single-row query takes a faulty pre-evaluation path in MySQL 8.4.11; keep both input rows.
        var rows = query.queryable("SELECT 7 AS " + quote.getQuoteName("number") + ",TRUE AS " + quote.getQuoteName("enabled") + from
                + " UNION ALL SELECT 9,FALSE" + from, PrimitiveScalars.class).orderBy(p -> p.number().asc());
        var result = rows.cloneQueryable().select(p -> {
            var pro = ProDsl.of(p);
            var empty = pro.window().orderBy(p.number()).rowsBetween(RowBoundary.following(1), RowBoundary.following(1));
            return Select.DRAFT.of(pro.overValue(p.number(), empty).firstValue(), pro.overValue(p.enabled(), empty).lastValue(),
                    pro.overValue(p.number(), empty).nthValue(1), pro.over(p.number().min(), empty), pro.over(p.number().max(), empty));
        }).toList().get(1);
        assertAll(() -> assertNull(result.getValue1()), () -> assertNull(result.getValue2()), () -> assertNull(result.getValue3()),
                () -> assertNull(result.getValue4()), () -> assertNull(result.getValue5()));
        assertNull(rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).overValue(p.number(),
                ProDsl.of(p).window().orderBy(p.number()).rowsBetween(RowBoundary.following(1), RowBoundary.following(1))).firstValue()).toList().get(1));
    }

    private static void verifyGroupedReport(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows) {
        var grouped = rows.get().groupBy(p -> GroupKeys.of(p.region(), p.day()))
                .select(g -> Select.DRAFT.of(g.key1(), g.key2(), g.groupTable().amount().sumBigDecimal()));
        var windows = grouped.select(p -> {
            var pro = ProDsl.of(p);
            var ordered = pro.window().partitionBy(p.value1()).orderBy(p.value2());
            var cumulative = ordered.rowsBetween(RowBoundary.unboundedPreceding(), RowBoundary.currentRow());
            var moving = ordered.rowsBetween(RowBoundary.preceding(1), RowBoundary.currentRow());
            var running = pro.over(p.value3().asBigDecimal().sumBigDecimal(), cumulative);
            var total = pro.over(p.value3().asBigDecimal().sumBigDecimal(), pro.window());
            return Select.DRAFT.of(p.value1(), p.value2(), p.value3(), running, total,
                    pro.over(p.value3().asBigDecimal().avg(), moving), pro.nativeCast(running, CommonTypes.decimal(19, 4)),
                    pro.nullIfValue(total, BigDecimal.ZERO));
        });
        var all = windows.cloneQueryable().orderBy(p -> {
            p.value1().asc();
            p.value2().asc();
        }).toList();
        assertEquals(5, all.size());
        assertDecimals(Arrays.asList("30", "35", "7", "10", null), all.stream().map(r -> r.getValue4()).toList());
        assertDecimals(List.of("45", "45", "45", "45", "45"), all.stream().map(r -> r.getValue5()).toList());
        assertDecimals(Arrays.asList("30", "17.5", "7", "5", null), all.stream().map(r -> r.getValue6()).toList());
        assertDecimals(Arrays.asList("30", "35", "7", "10", null), all.stream().map(r -> r.getValue7()).toList());
        assertDecimals(List.of("45", "45", "45", "45", "45"), all.stream().map(r -> r.getValue8()).toList());
        windows.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        var day = windows.cloneQueryable().where(p -> p.value2().eq(LocalDate.of(2024, 1, 2))).singleNotNull();
        assertDecimals(List.of("35", "45"), List.of(day.getValue4(), day.getValue5()));
        var region = windows.cloneQueryable().where(p -> p.value1().eq("A")).toList();
        assertEquals(2, region.size());
        assertDecimals(List.of("45", "45"), region.stream().map(r -> r.getValue5()).toList());
        var running = windows.cloneQueryable().where(p -> p.value4().eq(new BigDecimal("35"))).singleNotNull();
        assertEquals(LocalDate.of(2024, 1, 2), running.getValue2());
        assertEquals(2, windows.cloneQueryable().where(p -> p.value4().in(List.of(new BigDecimal("30"), new BigDecimal("35")))).toList().size());
        var filtered = windows.cloneQueryable().where(p -> p.value4().asBigDecimal().ge(BigDecimal.TEN))
                .orderBy(p -> {
                    p.value1().asc();
                    p.value2().asc();
                });
        filtered.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        assertEquals(3L, filtered.cloneQueryable().count());
        var page = filtered.cloneQueryable().toPageResult(2, 2);
        assertEquals(3L, page.getTotal());
        assertEquals(1, page.getData().size());
        assertEquals("B", page.getData().get(0).getValue1());
        assertEquals(LocalDate.of(2024, 1, 3), page.getData().get(0).getValue2());
    }

    private static void verifyDistinctBoundary(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows) {
        var windows = rows.get().select(p -> Select.DRAFT.of(ProDsl.of(p).over(p.amount().sumBigDecimal(),
                ProDsl.of(p).window().partitionBy(p.region()))));
        assertThrows(UnsupportedOperationException.class, () -> windows.cloneQueryable().distinct().count());
        var bounded = windows.cloneQueryable().distinct().toCteAs("eqp_window_values");
        assertEquals(3L, bounded.cloneQueryable().count());
        var page = bounded.cloneQueryable().toPageResult(1, 10);
        assertEquals(3L, page.getTotal());
        assertEquals(Set.of("35", "10", "NULL"), page.getData().stream()
                .map(r -> r.getValue1() == null ? "NULL" : r.getValue1().stripTrailingZeros().toPlainString()).collect(Collectors.toSet()));
    }

    private static void assertDecimals(List<String> expected, List<BigDecimal> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i) == null) assertNull(actual.get(i), "row " + i);
            else {
                assertNotNull(actual.get(i), "row " + i);
                assertEquals(0, new BigDecimal(expected.get(i)).compareTo(actual.get(i)), "row " + i + ": " + actual.get(i));
            }
        }
    }
}
