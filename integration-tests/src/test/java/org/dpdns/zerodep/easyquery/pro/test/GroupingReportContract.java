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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 仅用常量派生关系验证报表分组。真实 NULL 键、真实 0 与小计占位必须独立验证，
 * 不能以 SQL 出现 ROLLUP 关键字代替结果语义，也不能把集合顺序当作数据库隐式排序保证。
 */
final class GroupingReportContract {
    private GroupingReportContract() {
    }

    static void verify(DefaultEasyEntityQuery query) {
        var runtime = query.getEasyQueryClient().getRuntimeContext();
        var dialect = runtime.getService(SqlDialect.class);
        var quote = runtime.getQueryConfiguration().getDialect();
        String from = dialect == SqlDialect.HANA ? " FROM DUMMY" : "";
        String textType = dialect == SqlDialect.MYSQL ? "CHAR(20)" : "VARCHAR(20)";
        String sql = "SELECT 1 AS " + quote.getQuoteName("id") + ",CAST(NULL AS " + textType + ") AS " + quote.getQuoteName("region")
                + ",CAST('2024-01-01' AS DATE) AS " + quote.getQuoteName("report_day")
                + ",CAST(10 AS DECIMAL(19,4)) AS " + quote.getQuoteName("amount") + from
                + " UNION ALL SELECT 2,'A',CAST(NULL AS DATE),20" + from
                + " UNION ALL SELECT 3,'A',CAST('2024-01-01' AS DATE),30" + from;
        var rows = query.queryable(sql, ReportSale.class);
        var grouped = rows.cloneQueryable().groupBy(p -> GroupKeys.of(p.region(), p.day()));
        var report = ProGroups.rollup(grouped, g -> Select.DRAFT.of(g.key1(), g.key2(),
                g.groupTable().amount().sumBigDecimal(), ProGroups.mask(g, g.key1(), g.key2()), g.count()));
        var result = report.cloneQueryable().toList();
        assertEquals(6, result.size());
        assertEquals(3L, result.stream().filter(r -> r.getValue4() == 0).count());
        assertEquals(2L, result.stream().filter(r -> r.getValue4() == 1).count());
        var grand = result.stream().filter(r -> r.getValue4() == 3).findFirst().orElseThrow();
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
        var displayed = report.cloneQueryable();
        displayed.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        var onlyA = displayed.where(p -> p.value1().eq("A")).toList();
        assertEquals(3, onlyA.size());
        assertTrue(onlyA.stream().allMatch(r -> "A".equals(r.getValue1())));
        var details = report.cloneQueryable();
        details.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        assertEquals(3, details.where(p -> p.value4().eq(0)).toList().size());

        // 带参数的计算先固定为派生列，GROUPING 与 GROUP BY 复用列引用而不是不同的参数位置。
        var projected = rows.cloneQueryable().select(p -> Select.DRAFT.of(ProDsl.of(p).nullIfValue(p.region(), "not-a-region"), p.day(), p.amount()));
        var computedGroups = projected.groupBy(p -> GroupKeys.of(p.value1(), p.value2()));
        var computed = ProGroups.rollup(computedGroups,
                g -> Select.DRAFT.of(g.key1(), g.key2(), g.groupTable().value3().asBigDecimal().sumBigDecimal(), ProGroups.mask(g, g.key1(), g.key2())));
        assertEquals(6, computed.cloneQueryable().toList().size());
        assertEquals(6L, computed.cloneQueryable().count());
        assertEquals(computed.cloneQueryable().toSQL(), computed.cloneQueryable().toSQL());

        // 上游 clone 复制 builder 但复用 Proxy，自连接两侧必须分别创建来源，避免右侧绑定覆盖左侧。
        var joinedGroups = query.queryable(sql, ReportSale.class).leftJoin(query.queryable(sql, ReportSale.class), (left, right) -> left.id().eq(right.id()))
                .groupBy((left, right) -> GroupKeys.of(left.region(), right.day()));
        var joined = ProGroups.rollup(joinedGroups, g -> Select.DRAFT.of(g.key1(), g.key2(),
                g.groupTable().t2.amount().sumBigDecimal(), ProGroups.mask(g, g.key1(), g.key2())));
        var joinedRows = joined.cloneQueryable().toList();
        assertEquals(6, joinedRows.size());
        var joinedTotal = joinedRows.stream().filter(r -> r.getValue4() == 3).findFirst().orElseThrow().getValue3();
        assertEquals(0, new BigDecimal("60").compareTo(joinedTotal), () -> joined.cloneQueryable().toSQL() + "; total=" + joinedTotal);

        var empty = rows.cloneQueryable().where(p -> p.id().lt(0)).groupBy(p -> GroupKeys.of(p.region(), p.day()));
        assertTrue(ProGroups.rollup(empty, g -> Select.DRAFT.of(g.key1(), g.key2(), g.count())).toList().isEmpty());
        var emptyWithOr = empty.cloneQueryable().having(g -> {
            g.count().eq(0L);
            g.or(() -> g.count().gt(99L));
        });
        assertTrue(ProGroups.rollup(emptyWithOr, g -> Select.DRAFT.of(g.key1(), g.count())).toList().isEmpty());
        if (dialect != SqlDialect.MYSQL) {
            var nativeGroups = rows.cloneQueryable().groupBy(p -> GroupKeys.of(p.region(), p.day()));
            var sets = dialect == SqlDialect.POSTGRESQL
                    ? PgGroups.groupingSets(nativeGroups, g -> List.of(GroupingSet.of(g.key1()), GroupingSet.of(g.key2()), GroupingSet.total()),
                    g -> Select.DRAFT.of(g.key1(), g.key2(), g.count(), ProGroups.mask(g, g.key1(), g.key2())))
                    : HanaGroups.groupingSets(nativeGroups, g -> List.of(GroupingSet.of(g.key1()), GroupingSet.of(g.key2()), GroupingSet.total()),
                    g -> Select.DRAFT.of(g.key1(), g.key2(), g.count(), ProGroups.mask(g, g.key1(), g.key2())));
            assertEquals(5, sets.cloneQueryable().toList().size());
            assertEquals(5L, sets.cloneQueryable().count());
            assertEquals(5L, sets.cloneQueryable().toPageResult(1, 2).getTotal());
            var nativeEmpty = dialect == SqlDialect.POSTGRESQL
                    ? PgGroups.groupingSets(empty, g -> List.of(GroupingSet.of(g.key1()), GroupingSet.total()), g -> Select.DRAFT.of(g.key1(), g.count()))
                    : HanaGroups.groupingSets(empty, g -> List.of(GroupingSet.of(g.key1()), GroupingSet.total()), g -> Select.DRAFT.of(g.key1(), g.count()));
            assertEquals(dialect == SqlDialect.POSTGRESQL ? 1 : 0, nativeEmpty.toList().size());
        }
        verifyPrimitiveKeys(query, from);
    }

    private static void verifyPrimitiveKeys(DefaultEasyEntityQuery query, String from) {
        var quote = query.getEasyQueryClient().getRuntimeContext().getQueryConfiguration().getDialect();
        var rows = query.queryable("SELECT 0 AS " + quote.getQuoteName("number") + ",TRUE AS " + quote.getQuoteName("enabled")
                + from + " UNION ALL SELECT 7,FALSE" + from, PrimitiveScalars.class);
        var grouped = rows.cloneQueryable().groupBy(p -> GroupKeys.of(p.number()));
        Class<?> originalKeyType = grouped.get1Proxy().key1().getPropertyType();
        var report = ProGroups.rollup(grouped, g -> Select.DRAFT.of(g.key1(), g.count(), ProGroups.mask(g, g.key1())));
        var result = report.cloneQueryable().toList();
        assertEquals(3, result.size());
        assertTrue(result.stream().anyMatch(r -> Integer.valueOf(0).equals(r.getValue1()) && r.getValue3() == 0));
        assertNull(result.stream().filter(r -> r.getValue3() == 1).findFirst().orElseThrow().getValue1());
        assertTrue(report.cloneQueryable().toCteAs("primitive_totals").selectColumn(p -> p.value1()).toList().contains(null));
        assertTrue(report.cloneQueryable().select(p -> Select.DRAFT.of(p.value3(), p.value1())).toList().stream().anyMatch(r -> r.getValue2() == null));
        assertEquals(originalKeyType, grouped.get1Proxy().key1().getPropertyType());
        assertEquals(int.class, grouped.get1Proxy().key1().getTable().getEntityMetadata().getColumnNotNull("number").getPropertyType());
        assertEquals(2, grouped.select(g -> Select.DRAFT.of(g.key1(), g.count())).toList().size());

        var boxed = rows.cloneQueryable().groupBy(p -> GroupKeys.of(p.number()));
        assertTrue(ProGroups.rollup(boxed, g -> Select.of(NullableKey.class, g.key1().as("number"))).toList().stream()
                .anyMatch(r -> r.getNumber() == null));
        var primitive = rows.cloneQueryable().groupBy(p -> GroupKeys.of(p.number()));
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
