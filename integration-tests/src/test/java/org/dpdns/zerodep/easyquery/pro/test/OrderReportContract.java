package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.func.def.enums.OrderByModeEnum;
import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.ReportOrder;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportOrderProxy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复用现有条件聚合与排名，用固定业务结果验收类型提升、NULL 和报表层次。
 */
final class OrderReportContract {
    private OrderReportContract() {
    }

    static void verify(DefaultEasyEntityQuery query) {
        verifyConditionalMetrics(query);
        verifyTopRanks(query);
        verifyPromotionBeforeSumming(query);
    }

    private static void verifyConditionalMetrics(DefaultEasyEntityQuery query) {
        var decimal = CommonTypes.decimal(19, 4);
        var grouped = rows(query, orders()).groupBy(p -> GroupKeys.of(p.region())).select(g -> {
            var p = g.groupTable();
            var pro = ProDsl.of(p);
            var amount = pro.nativeCast(p.amount(), decimal);
            var quantity = pro.nativeCast(p.quantity(), decimal);
            // filter 会改变当前聚合对象，每个指标都从输入重新创建，不能共享同一个 SUM 后再改条件。
            return Select.DRAFT.of(g.key1(),
                    p.id().count().filter(() -> p.status().eq("PAID")),
                    p.id().count().filter(() -> p.status().eq("REFUND")),
                    p.customerId().count(true).filter(() -> p.status().eq("PAID")),
                    amount.sumBigDecimal().filter(() -> p.status().eq("PAID")),
                    quantity.sumBigDecimal().filter(() -> p.status().eq("PAID")),
                    amount.sumBigDecimal().filter(() -> p.status().eq("REFUND")),
                    p.amount().count().filter(() -> p.status().eq("PAID")));
        });
        var groups = grouped.cloneQueryable().orderBy(p -> p.value1().asc(true, OrderByModeEnum.NULLS_LAST)).toList();
        assertEquals(Arrays.asList("A", "B", "D", "E", "R", "U", null), groups.stream().map(row -> row.getValue1()).toList());
        assertEquals(List.of(3L, 2L, 2L, 1L, 0L, 1L, 1L), groups.stream().map(row -> row.getValue2()).toList());
        assertEquals(List.of(1L, 0L, 0L, 0L, 1L, 1L, 0L), groups.stream().map(row -> row.getValue3()).toList());
        assertEquals(List.of(2L, 2L, 2L, 1L, 0L, 1L, 0L), groups.stream().map(row -> row.getValue4()).toList());
        assertDecimals(Arrays.asList("50", "0", "40", "40", "0", "0", null), groups.stream().map(row -> row.getValue5()).toList());
        assertDecimals(Arrays.asList("5", "0", "4", "4", "0", "0", null), groups.stream().map(row -> row.getValue6()).toList());
        assertDecimals(List.of("-5", "0", "0", "0", "-7", "-2", "0"), groups.stream().map(row -> row.getValue7()).toList());
        assertEquals(List.of(3L, 1L, 2L, 1L, 0L, 0L, 0L), groups.stream().map(row -> row.getValue8()).toList());

        var report = grouped.select(p -> {
            var pro = ProDsl.of(p);
            var amount = pro.nativeCast(p.value5(), decimal);
            var quantity = pro.nativeCast(p.value6(), decimal);
            var total = pro.over(p.value5().asBigDecimal().sumBigDecimal(), pro.window());
            return Select.DRAFT.of(p.value1(), p.value2(), p.value3(), p.value4(), p.value5(),
                    pro.nativeCast(amount.divide(pro.nullIfValue(quantity, BigDecimal.ZERO)), CommonTypes.decimal(19, 6)),
                    pro.nativeCast(amount.divide(pro.nullIfValue(total, BigDecimal.ZERO)), CommonTypes.decimal(19, 6)),
                    p.value7());
        }).toCteAs("order_metrics");
        report.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        var rendered = report.toSQLResult();
        var copy = report.cloneQueryable().toSQLResult();
        assertEquals(rendered.getSQL(), copy.getSQL());
        assertEquals(rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList(),
                copy.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        var all = report.cloneQueryable().orderBy(p -> p.value1().asc(true, OrderByModeEnum.NULLS_LAST)).toList();
        assertDecimals(Arrays.asList("10", null, "10", "10", null, null, null), all.stream().map(row -> row.getValue6()).toList());
        assertDecimals(Arrays.asList("0.384615", "0", "0.307692", "0.307692", "0", "0", null),
                all.stream().map(row -> row.getValue7()).toList());
        var region = report.cloneQueryable().where(p -> p.value1().eq("A")).singleNotNull();
        assertDecimal("0.384615", region.getValue7());
        assertEquals(7L, report.cloneQueryable().count());
        var page = report.cloneQueryable().orderBy(p -> p.value1().asc(true, OrderByModeEnum.NULLS_LAST)).toPageResult(2, 3);
        assertEquals(7L, page.getTotal());
        assertEquals(List.of("E", "R", "U"), page.getData().stream().map(row -> row.getValue1()).toList());

        var empty = rows(query, orders()).where(p -> p.id().lt(0)).select(p -> Select.DRAFT.of(
                p.id().count().filter(() -> p.status().eq("PAID")),
                p.amount().sumBigDecimal().filter(() -> p.status().eq("PAID")))).singleNotNull();
        assertEquals(0L, empty.getValue1());
        assertNull(empty.getValue2(), "SUM over an empty input stays NULL despite SUM.filter using ELSE 0 in nonempty groups");
    }

    private static void verifyTopRanks(DefaultEasyEntityQuery query) {
        var ranked = rows(query, orders()).where(p -> {
                    p.status().eq("PAID");
                    p.amount().isNotNull();
                }).select(p -> Select.DRAFT.of(p.id(), p.region(), p.amount(),
                        p.expression().rankOver().partitionBy(p.region()).orderByDescending(p.amount()),
                        p.expression().denseRankOver().partitionBy(p.region()).orderByDescending(p.amount()),
                        p.expression().rowNumberOver().partitionBy(p.region()).orderByDescending(p.amount()).orderBy(p.id())))
                .toCteAs("ranked_paid_orders");
        ranked.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        var topPeers = ranked.cloneQueryable().where(p -> p.value4().eq(1L)).orderBy(p -> {
            p.value2().asc();
            p.value1().asc();
        });
        assertEquals(List.of(1, 9, 4, 7, 10), topPeers.cloneQueryable().toList().stream().map(row -> row.getValue1()).toList());
        assertEquals(5L, topPeers.cloneQueryable().count());
        var page = topPeers.cloneQueryable().toPageResult(2, 2);
        assertEquals(5L, page.getTotal());
        assertEquals(List.of(4, 7), page.getData().stream().map(row -> row.getValue1()).toList());
        assertEquals(List.of(1, 4, 7, 10), ranked.cloneQueryable().where(p -> p.value6().eq(1L)).orderBy(p -> p.value1().asc())
                .toList().stream().map(row -> row.getValue1()).toList());
        assertEquals(6L, ranked.cloneQueryable().where(p -> p.value4().asLong().le(2L)).count());
        assertEquals(7L, ranked.cloneQueryable().where(p -> p.value5().asLong().le(2L)).count());
        assertEquals(ranked.toSQL(), ranked.cloneQueryable().toSQL());
    }

    private static void verifyPromotionBeforeSumming(DefaultEasyEntityQuery query) {
        var large = rows(query, List.of(new ReportOrder(1, "large", 1, "PAID", 1_500_000_000, "1500000000"),
                new ReportOrder(2, "large", 2, "PAID", 1_500_000_000, "1500000000")));
        var total = large.select(p -> {
            var pro = ProDsl.of(p);
            var quantity = pro.nativeCast(p.quantity(), CommonTypes.decimal(19, 4)).sumBigDecimal();
            var amount = pro.nativeCast(p.amount(), CommonTypes.decimal(19, 4)).sumBigDecimal();
            return Select.DRAFT.of(quantity, amount.divide(pro.nullIfValue(quantity, BigDecimal.ZERO)));
        }).singleNotNull();
        assertDecimal("3000000000", total.getValue1());
        assertDecimal("1", total.getValue2());
    }

    private static List<ReportOrder> orders() {
        return List.of(
                new ReportOrder(1, "A", 1, "PAID", 2, "20"),
                new ReportOrder(2, "A", 2, "PAID", 1, "10"),
                new ReportOrder(3, "A", 1, "REFUND", -1, "-5"),
                new ReportOrder(4, "B", 3, "PAID", 0, "0"),
                new ReportOrder(5, "B", 4, "PAID", 0, null),
                new ReportOrder(6, null, null, "PAID", null, null),
                // 单笔单位价不同且权重不同，简单平均单位价不能冒充 40 / 4 的加权结果。
                new ReportOrder(7, "D", 5, "PAID", 1, "30"),
                new ReportOrder(8, "D", 6, "PAID", 3, "10"),
                new ReportOrder(9, "A", 1, "PAID", 2, "20"),
                new ReportOrder(10, "E", 7, "PAID", 4, "40"),
                new ReportOrder(11, "R", 8, "REFUND", -1, "-7"),
                new ReportOrder(12, "U", 9, "PAID", null, null),
                new ReportOrder(13, "U", 9, "REFUND", -1, "-2"));
    }

    private static EntityQueryable<ReportOrderProxy, ReportOrder> rows(DefaultEasyEntityQuery query, List<ReportOrder> orders) {
        var runtime = query.getRuntimeContext();
        var dialect = runtime.getService(SqlDialect.class);
        var quote = runtime.getQueryConfiguration().getDialect();
        String textType = switch (dialect) {
            case MYSQL -> "CHAR(20)";
            case POSTGRESQL -> "VARCHAR(20)";
            case HANA -> "NVARCHAR(20)";
        };
        String from = dialect == SqlDialect.HANA ? " FROM DUMMY" : "";
        var statements = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        for (var order : orders) {
            statements.add("SELECT " + order.getId() + " AS " + quote.getQuoteName("id")
                    + ",CAST(? AS " + textType + ") AS " + quote.getQuoteName("region")
                    + "," + integer(order.getCustomerId()) + " AS " + quote.getQuoteName("customer_id")
                    + ",CAST(? AS " + textType + ") AS " + quote.getQuoteName("status")
                    + "," + integer(order.getQuantity()) + " AS " + quote.getQuoteName("quantity")
                    + ",CAST(? AS DECIMAL(19,4)) AS " + quote.getQuoteName("amount") + from);
            parameters.add(order.getRegion());
            parameters.add(order.getStatus());
            parameters.add(order.getAmount());
        }
        return query.queryable(String.join(" UNION ALL ", statements), ReportOrder.class, parameters);
    }

    private static String integer(Integer value) {
        return value == null ? "NULL" : value.toString();
    }

    private static void assertDecimals(List<String> expected, List<BigDecimal> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            if (expected.get(i) == null) assertNull(actual.get(i), "row " + i);
            else assertDecimal(expected.get(i), actual.get(i));
        }
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "Expected " + expected + ", got " + actual);
    }
}
