package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportSale;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportSaleProxy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用稠密日历关系验收报表组合；业务表或参数化日历均可复用相同查询链，无需另建报表执行器。
 */
final class CalendarReportContract {
    private CalendarReportContract() {
    }

    static void verify(DefaultEasyEntityQuery query) {
        verify(query, false);
        verify(query, true);
    }

    private static void verify(DefaultEasyEntityQuery query, boolean emptyFacts) {
        var months = IntStream.rangeClosed(1, 4).mapToObj(month -> LocalDate.of(2024, month, 1)).toList();
        var calendar = calendar(query, months).select(p -> Select.DRAFT.of(p.day()));
        var facts = facts(query);
        if (emptyFacts) facts.where(p -> p.id().lt(0));
        var monthly = facts.groupBy(p -> GroupKeys.of(ProDsl.of(p).truncateDate(p.day(), TruncationUnit.MONTH)))
                .select(g -> Select.DRAFT.of(g.key1(), g.groupTable().amount().sumBigDecimal(), g.count()));
        // 事实过滤必须先于分组或留在 ON；对右表放外层 WHERE 会丢失没有事实的日历月。
        var filled = calendar.leftJoin(monthly, (month, total) -> month.value1().eq(total.value1()))
                .select((month, total) -> Select.DRAFT.of(month.value1(), total.value2(),
                        total.value3().asLong().nullOrDefault(0L),
                        total.value2().asBigDecimal().nullOrDefault(BigDecimal.ZERO)));
        // LAG 继续复用上游 API。CTE 明确锁定补齐后的窗口边界，展示过滤不能回推而改变上一月。
        var report = filled.select(p -> Select.DRAFT.of(p.value1(), p.value2(), p.value3(), p.value4(),
                        p.value2().asBigDecimal().offset(over -> over.orderBy(p.value1())).prev(1)))
                .toCteAs("calendar_report");
        report.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        var result = report.cloneQueryable().orderBy(p -> p.value1().asc()).toList();
        assertEquals(months, result.stream().map(row -> row.getValue1()).toList());
        assertDecimals(emptyFacts ? Arrays.asList(null, null, null, null) : Arrays.asList("30", null, "5", null),
                result.stream().map(row -> row.getValue2()).toList());
        assertEquals(emptyFacts ? List.of(0L, 0L, 0L, 0L) : List.of(2L, 0L, 1L, 1L),
                result.stream().map(row -> row.getValue3()).toList());
        assertDecimals(emptyFacts ? List.of("0", "0", "0", "0") : List.of("30", "0", "5", "0"),
                result.stream().map(row -> row.getValue4()).toList());
        assertDecimals(emptyFacts ? Arrays.asList(null, null, null, null) : Arrays.asList(null, "30", null, "5"),
                result.stream().map(row -> row.getValue5()).toList());
        assertEquals(4L, report.cloneQueryable().count());
        var page = report.cloneQueryable().orderBy(p -> p.value1().asc()).toPageResult(2, 2);
        assertEquals(4L, page.getTotal());
        assertEquals(months.subList(2, 4), page.getData().stream().map(row -> row.getValue1()).toList());
        var march = report.cloneQueryable().where(p -> p.value1().eq(months.get(2))).singleNotNull();
        assertNull(march.getValue5(), "The previous calendar month is February, not the previous existing sale in January");
    }

    private static EntityQueryable<ReportSaleProxy, ReportSale> calendar(DefaultEasyEntityQuery query, List<LocalDate> months) {
        var dialect = query.getEasyQueryClient().getRuntimeContext().getQueryConfiguration().getDialect();
        String sql = months.stream().map(ignored -> "SELECT CAST(? AS DATE) AS " + dialect.getQuoteName("report_day") + from(query))
                .collect(Collectors.joining(" UNION ALL "));
        return query.queryable(sql, ReportSale.class, new ArrayList<>(months));
    }

    private static EntityQueryable<ReportSaleProxy, ReportSale> facts(DefaultEasyEntityQuery query) {
        var dialect = query.getEasyQueryClient().getRuntimeContext().getQueryConfiguration().getDialect();
        String sql = IntStream.rangeClosed(1, 4).mapToObj(id -> "SELECT " + id + " AS " + dialect.getQuoteName("id")
                + ",CAST(? AS DATE) AS " + dialect.getQuoteName("report_day")
                + ",CAST(? AS DECIMAL(19,4)) AS " + dialect.getQuoteName("amount") + from(query)).collect(Collectors.joining(" UNION ALL "));
        return query.queryable(sql, ReportSale.class, Arrays.asList(
                LocalDate.of(2024, 1, 15), BigDecimal.TEN,
                LocalDate.of(2024, 1, 31), new BigDecimal("20"),
                LocalDate.of(2024, 3, 10), new BigDecimal("5"),
                LocalDate.of(2024, 4, 10), null));
    }

    private static String from(DefaultEasyEntityQuery query) {
        return query.getEasyQueryClient().getRuntimeContext().getService(SqlDialect.class) == SqlDialect.HANA ? " FROM DUMMY" : "";
    }

    private static void assertDecimals(List<String> expected, List<BigDecimal> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i) == null) assertNull(actual.get(i), "row " + i);
            else assertEquals(0, new BigDecimal(expected.get(i)).compareTo(actual.get(i)), "row " + i);
        }
    }
}
