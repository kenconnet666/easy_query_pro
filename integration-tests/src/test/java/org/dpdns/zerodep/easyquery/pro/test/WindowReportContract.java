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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.easy.query.core.proxy.core.draft.proxy.Draft7Proxy;
import com.easy.query.core.proxy.core.draft.Draft7;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RowsWindowSpec;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PrimitiveScalarsProxy;
import com.easy.query.core.proxy.core.draft.Draft5;
import com.easy.query.core.proxy.core.draft.proxy.Draft3Proxy;
import com.easy.query.core.proxy.core.draft.Draft3;
import com.easy.query.core.proxy.core.draft.proxy.Draft8Proxy;
import com.easy.query.core.proxy.core.draft.Draft8;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.api.pagination.EasyPageResult;
import com.easy.query.core.proxy.core.draft.proxy.Draft1Proxy;
import com.easy.query.core.proxy.core.draft.Draft1;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三库共用的报表结果契约。重复日期、不同分区和 NULL 金额用于区分 ROWS、默认 peer frame 与全局窗口，
 * 断言固定业务结果，避免只检查 SQL 文本而漏掉筛选位置或读取器造成的错误。
 */
final class WindowReportContract {
    private WindowReportContract() {
    }

    static List<ReportSale> sales() {
        return Arrays.asList(new ReportSale(1, "A", LocalDate.of(2024, 1, 1), "10"),
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
        EntityQueryable<Draft7Proxy<Integer, BigDecimal, BigDecimal, BigDecimal, BigDecimal, Long, BigDecimal>, Draft7<Integer, BigDecimal, BigDecimal, BigDecimal, BigDecimal, Long, BigDecimal>> selected = rows.get().select(p -> {
            ProDsl pro = ProDsl.of(p);
            RowsWindowSpec ordered = pro.window().partitionBy(p.region()).orderBy(p.id());
            RowsWindowSpec entire = ordered.rowsBetween(RowBoundary.unboundedPreceding(), RowBoundary.unboundedFollowing());
            RowsWindowSpec next = ordered.rowsBetween(RowBoundary.following(1), RowBoundary.following(2));
            return Select.DRAFT.of(p.id(), pro.overValue(p.amount(), entire).firstValue(),
                    pro.overValue(p.amount(), entire).lastValue(), pro.overValue(p.amount(), entire).nthValue(2),
                    pro.overValue(p.amount(), ordered).lastValue(), pro.over(p.amount().count(), next),
                    pro.over(p.amount().sumBigDecimal(), next));
        }).orderBy(p -> p.value1().asc());
        List<Draft7<Integer, BigDecimal, BigDecimal, BigDecimal, BigDecimal, Long, BigDecimal>> result = selected.cloneQueryable().toList();
        assertDecimals(Arrays.asList("10", "10", "10", "7", "7", null), result.stream().map(r -> r.getValue2()).collect(Collectors.toList()));
        assertDecimals(Arrays.asList("5", "5", "5", "3", "3", null), result.stream().map(r -> r.getValue3()).collect(Collectors.toList()));
        assertDecimals(Arrays.asList("20", "20", "20", "3", "3", null), result.stream().map(r -> r.getValue4()).collect(Collectors.toList()));
        assertDecimals(Arrays.asList("10", "20", "5", "7", "3", null), result.stream().map(r -> r.getValue5()).collect(Collectors.toList()));
        assertEquals(Arrays.asList(2L, 1L, 0L, 1L, 0L, 0L), result.stream().map(r -> r.getValue6()).collect(Collectors.toList()));
        assertDecimals(Arrays.asList("25", "5", null, "3", null, null), result.stream().map(r -> r.getValue7()).collect(Collectors.toList()));
        assertEquals(6L, selected.cloneQueryable().count());
    }

    static void verifyPrimitiveNulls(DefaultEasyEntityQuery query) {
        QueryRuntimeContext runtime = query.getEasyQueryClient().getRuntimeContext();
        SQLKeyword quote = runtime.getQueryConfiguration().getDialect();
        String from = runtime.getService(SqlDialect.class) == SqlDialect.HANA ? " FROM DUMMY" : "";
        // MySQL 8.4.11 对常量单行有预执行优化差异；保留两行，再检查最后一行的空 frame。
        EntityQueryable<PrimitiveScalarsProxy, PrimitiveScalars> rows = query.queryable("SELECT 7 AS " + quote.getQuoteName("number") + ",TRUE AS " + quote.getQuoteName("enabled") + from
                + " UNION ALL SELECT 9,FALSE" + from, PrimitiveScalars.class).orderBy(p -> p.number().asc());
        Draft5<Integer, Boolean, Integer, Integer, Integer> result = rows.cloneQueryable().select(p -> {
            ProDsl pro = ProDsl.of(p);
            RowsWindowSpec empty = pro.window().orderBy(p.number()).rowsBetween(RowBoundary.following(1), RowBoundary.following(1));
            return Select.DRAFT.of(pro.overValue(p.number(), empty).firstValue(), pro.overValue(p.enabled(), empty).lastValue(),
                    pro.overValue(p.number(), empty).nthValue(1), pro.over(p.number().min(), empty), pro.over(p.number().max(), empty));
        }).toList().get(1);
        assertAll(() -> assertNull(result.getValue1()), () -> assertNull(result.getValue2()), () -> assertNull(result.getValue3()),
                () -> assertNull(result.getValue4()), () -> assertNull(result.getValue5()));
        assertNull(rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).overValue(p.number(),
                ProDsl.of(p).window().orderBy(p.number()).rowsBetween(RowBoundary.following(1), RowBoundary.following(1))).firstValue()).toList().get(1));
    }

    private static void verifyGroupedReport(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows) {
        EntityQueryable<Draft3Proxy<String, LocalDate, BigDecimal>, Draft3<String, LocalDate, BigDecimal>> grouped = rows.get().groupBy(p -> GroupKeys.of(p.region(), p.day()))
                .select(g -> Select.DRAFT.of(g.key1(), g.key2(), g.groupTable().amount().sumBigDecimal()));
        EntityQueryable<Draft8Proxy<String, LocalDate, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal>, Draft8<String, LocalDate, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal>> windows = grouped.select(p -> {
            ProDsl pro = ProDsl.of(p);
            RowsWindowSpec ordered = pro.window().partitionBy(p.value1()).orderBy(p.value2());
            RowsWindowSpec cumulative = ordered.rowsBetween(RowBoundary.unboundedPreceding(), RowBoundary.currentRow());
            RowsWindowSpec moving = ordered.rowsBetween(RowBoundary.preceding(1), RowBoundary.currentRow());
            NumberTypeExpression<BigDecimal> running = pro.over(p.value3().asBigDecimal().sumBigDecimal(), cumulative);
            NumberTypeExpression<BigDecimal> total = pro.over(p.value3().asBigDecimal().sumBigDecimal(), pro.window());
            return Select.DRAFT.of(p.value1(), p.value2(), p.value3(), running, total,
                    pro.over(p.value3().asBigDecimal().avg(), moving), pro.nativeCast(running, CommonTypes.decimal(19, 4)),
                    pro.nullIfValue(total, BigDecimal.ZERO));
        });
        List<Draft8<String, LocalDate, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal>> all = windows.cloneQueryable().orderBy(p -> {
            p.value1().asc();
            p.value2().asc();
        }).toList();
        assertEquals(5, all.size());
        assertDecimals(Arrays.asList("30", "35", "7", "10", null), all.stream().map(r -> r.getValue4()).collect(Collectors.toList()));
        assertDecimals(Arrays.asList("45", "45", "45", "45", "45"), all.stream().map(r -> r.getValue5()).collect(Collectors.toList()));
        assertDecimals(Arrays.asList("30", "17.5", "7", "5", null), all.stream().map(r -> r.getValue6()).collect(Collectors.toList()));
        assertDecimals(Arrays.asList("30", "35", "7", "10", null), all.stream().map(r -> r.getValue7()).collect(Collectors.toList()));
        assertDecimals(Arrays.asList("45", "45", "45", "45", "45"), all.stream().map(r -> r.getValue8()).collect(Collectors.toList()));
        // 直接 EQ/IN 才覆盖上游 SMART 的目标下推分支；只测试 asBigDecimal().ge(...) 会漏掉这个问题。
        windows.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        Draft8<String, LocalDate, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal> day = windows.cloneQueryable().where(p -> p.value2().eq(LocalDate.of(2024, 1, 2))).singleNotNull();
        assertDecimals(Arrays.asList("35", "45"), Arrays.asList(day.getValue4(), day.getValue5()));
        List<Draft8<String, LocalDate, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal>> region = windows.cloneQueryable().where(p -> p.value1().eq("A")).toList();
        assertEquals(2, region.size());
        assertDecimals(Arrays.asList("45", "45"), region.stream().map(r -> r.getValue5()).collect(Collectors.toList()));
        Draft8<String, LocalDate, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal> running = windows.cloneQueryable().where(p -> p.value4().eq(new BigDecimal("35"))).singleNotNull();
        assertEquals(LocalDate.of(2024, 1, 2), running.getValue2());
        assertEquals(2, windows.cloneQueryable().where(p -> p.value4().in(Arrays.asList(new BigDecimal("30"), new BigDecimal("35")))).toList().size());
        EntityQueryable<Draft8Proxy<String, LocalDate, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal>, Draft8<String, LocalDate, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal>> filtered = windows.cloneQueryable().where(p -> p.value4().asBigDecimal().ge(BigDecimal.TEN))
                .orderBy(p -> {
                    p.value1().asc();
                    p.value2().asc();
                });
        filtered.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        assertEquals(3L, filtered.cloneQueryable().count());
        EasyPageResult<Draft8<String, LocalDate, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal>> page = filtered.cloneQueryable().toPageResult(2, 2);
        assertEquals(3L, page.getTotal());
        assertEquals(1, page.getData().size());
        assertEquals("B", page.getData().get(0).getValue1());
        assertEquals(LocalDate.of(2024, 1, 3), page.getData().get(0).getValue2());
    }

    private static void verifyDistinctBoundary(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows) {
        EntityQueryable<Draft1Proxy<BigDecimal>, Draft1<BigDecimal>> windows = rows.get().select(p -> Select.DRAFT.of(ProDsl.of(p).over(p.amount().sumBigDecimal(),
                ProDsl.of(p).window().partitionBy(p.region()))));
        assertThrows(UnsupportedOperationException.class, () -> windows.cloneQueryable().distinct().count());
        EntityQueryable<Draft1Proxy<BigDecimal>, Draft1<BigDecimal>> bounded = windows.cloneQueryable().distinct().toCteAs("eqp_window_values");
        assertEquals(3L, bounded.cloneQueryable().count());
        EasyPageResult<Draft1<BigDecimal>> page = bounded.cloneQueryable().toPageResult(1, 10);
        assertEquals(3L, page.getTotal());
        assertEquals(new HashSet<>(Arrays.asList("35", "10", "NULL")), page.getData().stream()
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
