package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProGroups;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.group.GroupingSet;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaGroups;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgGroups;
import org.dpdns.zerodep.easyquery.pro.test.entity.PrimitiveScalars;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportSaleProxy;
import com.easy.query.core.proxy.grouping.proxy.Grouping2Proxy;
import com.easy.query.core.proxy.columns.types.SQLStringTypeColumn;
import com.easy.query.core.proxy.columns.types.SQLLocalDateTypeColumn;
import com.easy.query.core.proxy.grouping.Grouping2;
import com.easy.query.core.proxy.core.draft.proxy.Draft5Proxy;
import com.easy.query.core.proxy.core.draft.Draft5;
import com.easy.query.core.proxy.core.draft.proxy.Draft3Proxy;
import com.easy.query.core.proxy.core.draft.Draft3;
import com.easy.query.core.proxy.columns.types.SQLAnyTypeColumn;
import com.easy.query.core.proxy.core.draft.proxy.Draft4Proxy;
import com.easy.query.core.proxy.core.draft.Draft4;
import com.easy.query.core.common.tuple.MergeTuple2;
import java.util.Arrays;
import com.easy.query.core.proxy.core.draft.proxy.Draft2Proxy;
import com.easy.query.core.proxy.core.draft.Draft2;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PrimitiveScalarsProxy;
import com.easy.query.core.proxy.grouping.proxy.Grouping1Proxy;
import com.easy.query.core.proxy.columns.types.SQLIntegerTypeColumn;
import com.easy.query.core.proxy.grouping.Grouping1;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 仅用常量派生关系验证报表分组。真实 NULL 键、真实 0 与小计占位必须独立验证，
 * 不能以 SQL 出现 ROLLUP 关键字代替结果语义，也不能把集合顺序当作数据库隐式排序保证。
 */
final class GroupingReportContract {
    private GroupingReportContract() {
    }

    static void verify(DefaultEasyEntityQuery query) {
        QueryRuntimeContext runtime = query.getEasyQueryClient().getRuntimeContext();
        SqlDialect dialect = runtime.getService(SqlDialect.class);
        SQLKeyword quote = runtime.getQueryConfiguration().getDialect();
        String from = dialect == SqlDialect.HANA ? " FROM DUMMY" : "";
        String textType = dialect == SqlDialect.MYSQL ? "CHAR(20)" : "VARCHAR(20)";
        String sql = "SELECT 1 AS " + quote.getQuoteName("id") + ",CAST(NULL AS " + textType + ") AS " + quote.getQuoteName("region")
                + ",CAST('2024-01-01' AS DATE) AS " + quote.getQuoteName("report_day")
                + ",CAST(10 AS DECIMAL(19,4)) AS " + quote.getQuoteName("amount") + from
                + " UNION ALL SELECT 2,'A',CAST(NULL AS DATE),20" + from
                + " UNION ALL SELECT 3,'A',CAST('2024-01-01' AS DATE),30" + from;
        EntityQueryable<ReportSaleProxy, ReportSale> rows = query.queryable(sql, ReportSale.class);
        EntityQueryable<Grouping2Proxy<SQLStringTypeColumn<ReportSaleProxy>, String, SQLLocalDateTypeColumn<ReportSaleProxy>, LocalDate, ReportSaleProxy>, Grouping2<String, LocalDate>> grouped = rows.cloneQueryable().groupBy(p -> GroupKeys.of(p.region(), p.day()));
        EntityQueryable<Draft5Proxy<String, LocalDate, BigDecimal, Integer, Long>, Draft5<String, LocalDate, BigDecimal, Integer, Long>> report = ProGroups.rollup(grouped, g -> Select.DRAFT.of(g.key1(), g.key2(),
                g.groupTable().amount().sumBigDecimal(), ProGroups.mask(g, g.key1(), g.key2()), g.count()));
        List<Draft5<String, LocalDate, BigDecimal, Integer, Long>> result = report.cloneQueryable().toList();
        assertEquals(6, result.size());
        assertEquals(3L, result.stream().filter(r -> r.getValue4() == 0).count());
        assertEquals(2L, result.stream().filter(r -> r.getValue4() == 1).count());
        Draft5<String, LocalDate, BigDecimal, Integer, Long> grand = result.stream().filter(r -> r.getValue4() == 3).findFirst().get();
        assertNull(grand.getValue1());
        assertNull(grand.getValue2());
        assertEquals(0, new BigDecimal("60").compareTo(grand.getValue3()));
        assertEquals(3L, grand.getValue5());
        assertTrue(result.stream().anyMatch(r -> r.getValue1() == null && LocalDate.of(2024, 1, 1).equals(r.getValue2()) && r.getValue4() == 0));
        assertEquals(6L, report.cloneQueryable().count());
        assertEquals(6L, report.cloneQueryable().toPageResult(1, 2).getTotal());
        assertEquals(6, report.cloneQueryable().toCteAs("grouped_sales").toList().size());
        assertEquals(3, grouped.select(g -> Select.DRAFT.of(g.key1(), g.key2(), g.count())).toList().size());

        // 汇总后只展示 A；若 SMART 将条件移到汇总前，会重新生成本不应展示的 NULL 总计。
        EntityQueryable<Draft5Proxy<String, LocalDate, BigDecimal, Integer, Long>, Draft5<String, LocalDate, BigDecimal, Integer, Long>> displayed = report.cloneQueryable();
        displayed.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        List<Draft5<String, LocalDate, BigDecimal, Integer, Long>> onlyA = displayed.where(p -> p.value1().eq("A")).toList();
        assertEquals(3, onlyA.size());
        assertTrue(onlyA.stream().allMatch(r -> "A".equals(r.getValue1())));
        EntityQueryable<Draft5Proxy<String, LocalDate, BigDecimal, Integer, Long>, Draft5<String, LocalDate, BigDecimal, Integer, Long>> details = report.cloneQueryable();
        details.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        assertEquals(3, details.where(p -> p.value4().eq(0)).toList().size());

        // 带参数的计算先固定为派生列，GROUPING 与 GROUP BY 复用列引用而不是不同的参数位置。
        EntityQueryable<Draft3Proxy<String, LocalDate, BigDecimal>, Draft3<String, LocalDate, BigDecimal>> projected = rows.cloneQueryable().select(p -> Select.DRAFT.of(ProDsl.of(p).nullIfValue(p.region(), "not-a-region"), p.day(), p.amount()));
        EntityQueryable<Grouping2Proxy<SQLAnyTypeColumn<Draft3Proxy<String, LocalDate, BigDecimal>, String>, String, SQLAnyTypeColumn<Draft3Proxy<String, LocalDate, BigDecimal>, LocalDate>, LocalDate, Draft3Proxy<String, LocalDate, BigDecimal>>, Grouping2<String, LocalDate>> computedGroups = projected.groupBy(p -> GroupKeys.of(p.value1(), p.value2()));
        EntityQueryable<Draft4Proxy<String, LocalDate, BigDecimal, Integer>, Draft4<String, LocalDate, BigDecimal, Integer>> computed = ProGroups.rollup(computedGroups,
                g -> Select.DRAFT.of(g.key1(), g.key2(), g.groupTable().value3().asBigDecimal().sumBigDecimal(), ProGroups.mask(g, g.key1(), g.key2())));
        assertEquals(6, computed.cloneQueryable().toList().size());
        assertEquals(6L, computed.cloneQueryable().count());
        assertEquals(computed.cloneQueryable().toSQL(), computed.cloneQueryable().toSQL());

        // 上游 clone 复制 builder 但复用 Proxy，自连接两侧必须分别创建来源，避免右侧绑定覆盖左侧。
        EntityQueryable<Grouping2Proxy<SQLStringTypeColumn<ReportSaleProxy>, String, SQLLocalDateTypeColumn<ReportSaleProxy>, LocalDate, MergeTuple2<ReportSaleProxy, ReportSaleProxy>>, Grouping2<String, LocalDate>> joinedGroups = query.queryable(sql, ReportSale.class).leftJoin(query.queryable(sql, ReportSale.class), (left, right) -> left.id().eq(right.id()))
                .groupBy((left, right) -> GroupKeys.of(left.region(), right.day()));
        EntityQueryable<Draft4Proxy<String, LocalDate, BigDecimal, Integer>, Draft4<String, LocalDate, BigDecimal, Integer>> joined = ProGroups.rollup(joinedGroups, g -> Select.DRAFT.of(g.key1(), g.key2(),
                g.groupTable().t2.amount().sumBigDecimal(), ProGroups.mask(g, g.key1(), g.key2())));
        List<Draft4<String, LocalDate, BigDecimal, Integer>> joinedRows = joined.cloneQueryable().toList();
        assertEquals(6, joinedRows.size());
        BigDecimal joinedTotal = joinedRows.stream().filter(r -> r.getValue4() == 3).findFirst().get().getValue3();
        assertEquals(0, new BigDecimal("60").compareTo(joinedTotal), () -> joined.cloneQueryable().toSQL() + "; total=" + joinedTotal);

        EntityQueryable<Grouping2Proxy<SQLStringTypeColumn<ReportSaleProxy>, String, SQLLocalDateTypeColumn<ReportSaleProxy>, LocalDate, ReportSaleProxy>, Grouping2<String, LocalDate>> empty = rows.cloneQueryable().where(p -> p.id().lt(0)).groupBy(p -> GroupKeys.of(p.region(), p.day()));
        assertTrue(ProGroups.rollup(empty, g -> Select.DRAFT.of(g.key1(), g.key2(), g.count())).toList().isEmpty());
        EntityQueryable<Grouping2Proxy<SQLStringTypeColumn<ReportSaleProxy>, String, SQLLocalDateTypeColumn<ReportSaleProxy>, LocalDate, ReportSaleProxy>, Grouping2<String, LocalDate>> emptyWithOr = empty.cloneQueryable().having(g -> {
            g.count().eq(0L);
            g.or(() -> g.count().gt(99L));
        });
        assertTrue(ProGroups.rollup(emptyWithOr, g -> Select.DRAFT.of(g.key1(), g.count())).toList().isEmpty());
        if (dialect != SqlDialect.MYSQL) {
            EntityQueryable<Grouping2Proxy<SQLStringTypeColumn<ReportSaleProxy>, String, SQLLocalDateTypeColumn<ReportSaleProxy>, LocalDate, ReportSaleProxy>, Grouping2<String, LocalDate>> nativeGroups = rows.cloneQueryable().groupBy(p -> GroupKeys.of(p.region(), p.day()));
            EntityQueryable<Draft4Proxy<String, LocalDate, Long, Integer>, Draft4<String, LocalDate, Long, Integer>> sets = dialect == SqlDialect.POSTGRESQL
                    ? PgGroups.groupingSets(nativeGroups, g -> Arrays.asList(GroupingSet.of(g.key1()), GroupingSet.of(g.key2()), GroupingSet.total()),
                    g -> Select.DRAFT.of(g.key1(), g.key2(), g.count(), ProGroups.mask(g, g.key1(), g.key2())))
                    : HanaGroups.groupingSets(nativeGroups, g -> Arrays.asList(GroupingSet.of(g.key1()), GroupingSet.of(g.key2()), GroupingSet.total()),
                    g -> Select.DRAFT.of(g.key1(), g.key2(), g.count(), ProGroups.mask(g, g.key1(), g.key2())));
            assertEquals(5, sets.cloneQueryable().toList().size());
            assertEquals(5L, sets.cloneQueryable().count());
            assertEquals(5L, sets.cloneQueryable().toPageResult(1, 2).getTotal());
            EntityQueryable<Draft2Proxy<String, Long>, Draft2<String, Long>> nativeEmpty = dialect == SqlDialect.POSTGRESQL
                    ? PgGroups.groupingSets(empty, g -> Arrays.asList(GroupingSet.of(g.key1()), GroupingSet.total()), g -> Select.DRAFT.of(g.key1(), g.count()))
                    : HanaGroups.groupingSets(empty, g -> Arrays.asList(GroupingSet.of(g.key1()), GroupingSet.total()), g -> Select.DRAFT.of(g.key1(), g.count()));
            assertEquals(dialect == SqlDialect.POSTGRESQL ? 1 : 0, nativeEmpty.toList().size());
        }
        verifyPrimitiveKeys(query, from);
    }

    private static void verifyPrimitiveKeys(DefaultEasyEntityQuery query, String from) {
        SQLKeyword quote = query.getEasyQueryClient().getRuntimeContext().getQueryConfiguration().getDialect();
        EntityQueryable<PrimitiveScalarsProxy, PrimitiveScalars> rows = query.queryable("SELECT 0 AS " + quote.getQuoteName("number") + ",TRUE AS " + quote.getQuoteName("enabled")
                + from + " UNION ALL SELECT 7,FALSE" + from, PrimitiveScalars.class);
        EntityQueryable<Grouping1Proxy<SQLIntegerTypeColumn<PrimitiveScalarsProxy>, Integer, PrimitiveScalarsProxy>, Grouping1<Integer>> grouped = rows.cloneQueryable().groupBy(p -> GroupKeys.of(p.number()));
        Class<?> originalKeyType = grouped.get1Proxy().key1().getPropertyType();
        EntityQueryable<Draft3Proxy<Integer, Long, Integer>, Draft3<Integer, Long, Integer>> report = ProGroups.rollup(grouped, g -> Select.DRAFT.of(g.key1(), g.count(), ProGroups.mask(g, g.key1())));
        List<Draft3<Integer, Long, Integer>> result = report.cloneQueryable().toList();
        assertEquals(3, result.size());
        assertTrue(result.stream().anyMatch(r -> Integer.valueOf(0).equals(r.getValue1()) && r.getValue3() == 0));
        assertNull(result.stream().filter(r -> r.getValue3() == 1).findFirst().get().getValue1());
        assertTrue(report.cloneQueryable().toCteAs("primitive_totals").selectColumn(p -> p.value1()).toList().contains(null));
        assertTrue(report.cloneQueryable().select(p -> Select.DRAFT.of(p.value3(), p.value1())).toList().stream().anyMatch(r -> r.getValue2() == null));
        assertEquals(originalKeyType, grouped.get1Proxy().key1().getPropertyType());
        assertEquals(int.class, grouped.get1Proxy().key1().getTable().getEntityMetadata().getColumnNotNull("number").getPropertyType());
        assertEquals(2, grouped.select(g -> Select.DRAFT.of(g.key1(), g.count())).toList().size());

        EntityQueryable<Grouping1Proxy<SQLIntegerTypeColumn<PrimitiveScalarsProxy>, Integer, PrimitiveScalarsProxy>, Grouping1<Integer>> boxed = rows.cloneQueryable().groupBy(p -> GroupKeys.of(p.number()));
        assertTrue(ProGroups.rollup(boxed, g -> Select.of(NullableKey.class, g.key1().as("number"))).toList().stream()
                .anyMatch(r -> r.getNumber() == null));
        EntityQueryable<Grouping1Proxy<SQLIntegerTypeColumn<PrimitiveScalarsProxy>, Integer, PrimitiveScalarsProxy>, Grouping1<Integer>> primitive = rows.cloneQueryable().groupBy(p -> GroupKeys.of(p.number()));
        assertEquals(2L, ProGroups.rollup(primitive, g -> Select.of(PrimitiveScalars.class, g.key1().as("number"))).toList().stream()
                .filter(r -> r.getNumber() == 0).count());
    }

    /**
     * 显式 DTO 的包装类型决定其 NULL 契约，不继承输入实体的 int 默认值。
     */
    public static class NullableKey {
        private Integer number;

        public Integer getNumber() {
            return number;
        }

        public void setNumber(Integer number) {
            this.number = number;
        }
    }
}
