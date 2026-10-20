package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RowBoundary;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportSaleProxy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复用销售 fixture 检验统计语义：五个有效金额均值为 9，总体方差 35.6、样本方差 44.5。
 * 第六行是 NULL，用于确认忽略样本而非填零；过滤出的 10/20 两项还能区分正确 CASE NULL 与错误 CASE 0。
 * 微小小数和 2^53 边界分别检验转换时机与明确的 Double 近似契约。
 */
final class StatisticsContract {
    private StatisticsContract() {
    }

    static void verify(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows, DataSource source) {
        verifyAggregatesAndFilters(rows);
        verifyEmptyAndSingleton(rows);
        verifyGroupsAndWindows(rows);
        verifyApproximatePrecision(rows);
        verifyJdbcTypes(rows, source);
    }

    private static void verifyJdbcTypes(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows, DataSource source) {
        var sql = rows.get().select(p -> {
            var pro = ProDsl.of(p);
            return Select.DRAFT.of(pro.variancePopulation(p.amount()), pro.varianceSample(p.amount()),
                    pro.standardDeviationPopulation(p.amount()), pro.standardDeviationSample(p.amount()));
        }).toSQLResult();
        assertTrue(sql.getSqlContext().getParameters().isEmpty());
        assertDoesNotThrow(() -> {
            try (var connection = source.getConnection(); var statement = connection.prepareStatement(sql.getSQL()); var result = statement.executeQuery()) {
                var metadata = result.getMetaData();
                assertEquals(4, metadata.getColumnCount());
                for (int column = 1; column <= 4; column++) {
                    assertEquals(Types.DOUBLE, metadata.getColumnType(column));
                    assertEquals(Double.class.getName(), metadata.getColumnClassName(column));
                }
            }
        });
    }

    private static void verifyAggregatesAndFilters(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows) {
        var summary = rows.get().select(p -> {
            var pro = ProDsl.of(p);
            return Select.DRAFT.of(pro.variancePopulation(p.amount()), pro.varianceSample(p.amount()),
                    pro.standardDeviationPopulation(p.amount()), pro.standardDeviationSample(p.amount()));
        });
        var result = summary.cloneQueryable().singleNotNull();
        close(35.6, result.getValue1());
        close(44.5, result.getValue2());
        close(Math.sqrt(35.6), result.getValue3());
        close(Math.sqrt(44.5), result.getValue4());
        assertEquals(1L, summary.cloneQueryable().toCteAs("eqp_statistics").count());
        var filtered = rows.get().select(p -> {
            var pro = ProDsl.of(p);
            return Select.DRAFT.of(pro.variancePopulation(p.amount()).filter(() -> p.id().le(2)),
                    pro.varianceSample(p.amount()).filter(() -> p.id().le(2)),
                    pro.standardDeviationPopulation(p.amount()).filter(() -> p.id().le(2)),
                    pro.standardDeviationSample(p.amount()).filter(() -> p.id().le(2)),
                    pro.variancePopulation(p.amount()).filter(() -> p.id().lt(0)));
        }).singleNotNull();
        close(25, filtered.getValue1());
        close(50, filtered.getValue2());
        close(5, filtered.getValue3());
        close(Math.sqrt(50), filtered.getValue4());
        assertNull(filtered.getValue5());
    }

    private static void verifyEmptyAndSingleton(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows) {
        for (int id : new int[]{0, 1, 6}) {
            var result = rows.get().where(p -> p.id().eq(id)).select(p -> {
                var pro = ProDsl.of(p);
                return Select.DRAFT.of(pro.variancePopulation(p.amount()), pro.varianceSample(p.amount()),
                        pro.standardDeviationPopulation(p.amount()), pro.standardDeviationSample(p.amount()));
            }).singleNotNull();
            if (id == 1) {
                close(0, result.getValue1());
                close(0, result.getValue3());
            } else {
                assertNull(result.getValue1());
                assertNull(result.getValue3());
            }
            assertNull(result.getValue2());
            assertNull(result.getValue4());
        }
    }

    private static void verifyGroupsAndWindows(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows) {
        var grouped = rows.get().groupBy(p -> GroupKeys.of(p.region())).select(g -> {
            var pro = ProDsl.of(g.groupTable());
            return Select.DRAFT.of(g.key1(), pro.variancePopulation(g.groupTable().amount()),
                    pro.varianceSample(g.groupTable().amount()));
        }).orderBy(p -> p.value1().asc());
        var groups = grouped.cloneQueryable().toList();
        assertEquals(List.of("A", "B", "C"), groups.stream().map(g -> g.getValue1()).toList());
        close(350D / 9, groups.get(0).getValue2());
        close(175D / 3, groups.get(0).getValue3());
        close(4, groups.get(1).getValue2());
        close(8, groups.get(1).getValue3());
        assertNull(groups.get(2).getValue2());
        assertNull(groups.get(2).getValue3());
        assertEquals(3L, grouped.cloneQueryable().count());
        assertEquals(3L, grouped.cloneQueryable().toPageResult(1, 2).getTotal());
        var aboveThreshold = rows.get().groupBy(p -> GroupKeys.of(p.region()))
                .having(g -> ProDsl.of(g.groupTable()).variancePopulation(g.groupTable().amount()).gt(10D))
                .select(g -> Select.DRAFT.of(g.key1(), ProDsl.of(g.groupTable()).variancePopulation(g.groupTable().amount())))
                .singleNotNull();
        assertEquals("A", aboveThreshold.getValue1());
        close(350D / 9, aboveThreshold.getValue2());
        var windows = rows.get().select(p -> {
            var pro = ProDsl.of(p);
            var cumulative = pro.window().partitionBy(p.region()).orderBy(p.id())
                    .rowsBetween(RowBoundary.unboundedPreceding(), RowBoundary.currentRow());
            return Select.DRAFT.of(p.id(), pro.over(pro.variancePopulation(p.amount()), cumulative),
                    pro.over(pro.varianceSample(p.amount()), cumulative),
                    pro.over(pro.standardDeviationSample(p.amount()), pro.window()),
                    pro.over(pro.variancePopulation(p.amount()).filter(() -> p.id().le(2)), cumulative));
        }).toCteAs("eqp_statistic_windows").orderBy(p -> p.value1().asc());
        var result = windows.cloneQueryable().toList();
        assertValues(Arrays.asList(0D, 25D, 350D / 9, 0D, 4D, null), result.stream().map(r -> r.getValue2()).toList());
        assertValues(Arrays.asList(null, 50D, 175D / 3, null, 8D, null), result.stream().map(r -> r.getValue3()).toList());
        assertValues(Arrays.asList(0D, 25D, 25D, null, null, null), result.stream().map(r -> r.getValue5()).toList());
        for (var row : result) close(Math.sqrt(44.5), row.getValue4());
        assertEquals(6L, windows.cloneQueryable().count());
    }

    private static void verifyApproximatePrecision(Supplier<EntityQueryable<ReportSaleProxy, ReportSale>> rows) {
        var result = rows.get().where(p -> p.id().le(2)).select(p -> {
            var pro = ProDsl.of(p);
            var tiny = pro.nativeCast(p.id(), CommonTypes.decimal(19, 4)).multiply(new BigDecimal("0.0001"));
            var large = pro.value(new BigDecimal("9007199254740991"), CommonTypes.decimal(20, 0)).add(p.id());
            return Select.DRAFT.of(pro.variancePopulation(tiny), pro.varianceSample(tiny),
                    pro.standardDeviationPopulation(tiny), pro.standardDeviationSample(tiny), pro.variancePopulation(large));
        }).singleNotNull();
        close(2.5e-9, result.getValue1());
        close(5e-9, result.getValue2());
        close(0.00005, result.getValue3());
        close(Math.sqrt(5e-9), result.getValue4());
        close(0, result.getValue5());
    }

    private static void assertValues(List<Double> expected, List<Double> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i) == null) assertNull(actual.get(i), "row " + i);
            else close(expected.get(i), actual.get(i));
        }
    }

    private static void close(double expected, Double actual) {
        assertNotNull(actual);
        assertEquals(expected, actual, Math.max(1e-13, Math.abs(expected) * 1e-10));
    }
}
