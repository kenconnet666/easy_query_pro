package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl;
import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaAggregateFunctionsIT {
    private DefaultEasyEntityQuery query;

    @BeforeEach
    void createClient() throws SQLException {
        query = HanaTestDatabase.client();
    }

    @Test
    void basicAggregatesKeepDistinctNullAndEmptySetSemantics() {
        var row = rows().select(p -> Select.DRAFT.of(p.expression().count(), p.amount().count(),
                p.amount().count(true), p.amount().sumBigDecimal(), p.amount().sumBigDecimal(true),
                p.amount().avg(), p.amount().avg(true), p.amount().max(), p.amount().min())).singleNotNull();
        assertEquals(5L, row.getValue1());
        assertEquals(4L, row.getValue2());
        assertEquals(3L, row.getValue3());
        assertDecimal("45", row.getValue4());
        assertDecimal("35", row.getValue5());
        assertDecimal("11.25", row.getValue6());
        assertEquals(35.0 / 3, row.getValue7().doubleValue(), 0.000001);
        assertDecimal("20", row.getValue8());
        assertDecimal("5", row.getValue9());

        var empty = rows().where(p -> p.id().lt(0)).select(p -> Select.DRAFT.of(
                p.expression().count(), p.amount().sumBigDecimal(), p.amount().avg(), p.amount().max())).singleNotNull();
        assertEquals(0L, empty.getValue1());
        assertNull(empty.getValue2());
        assertNull(empty.getValue3());
        assertNull(empty.getValue4());
    }

    @Test
    void groupingHavingAndConditionalAggregatesComposeWithClone() {
        var selection = rows().groupBy(p -> GroupKeys.of(p.name()))
                .having(g -> g.groupTable().amount().sumBigDecimal().gt(BigDecimal.TEN))
                .select(g -> Select.DRAFT.of(g.key1(), g.groupTable().amount().sumBigDecimal(),
                        g.groupTable().amount().sumBigDecimal().filter(() -> g.groupTable().id().le(2)),
                        g.groupTable().amount().count().filter(() -> g.groupTable().id().le(2))));
        assertEquals(selection.toSQL(), selection.cloneQueryable().toSQL());
        var rows = selection.toList();
        assertEquals(1, rows.size());
        assertEquals("A", rows.get(0).getValue1());
        assertDecimal("40", rows.get(0).getValue2());
        assertDecimal("20", rows.get(0).getValue3());
        assertEquals(2L, rows.get(0).getValue4());
    }

    @Test
    void stringAggregationBindsSeparatorsAndRetainsOrderingAndNulls() {
        String separator = "'中{0}";
        var selection = rows().selectColumn(p -> new StringTypeExpressionImpl<String>(
                p.getEntitySQLContext(), p.getTable(), null, fx -> fx.joining(args -> {
                    args.value(separator);
                    PropTypeColumn.columnFuncSelector(args, p.name());
                    args.sqlFunc(fx.anySQLFunction("{0} DESC", order -> PropTypeColumn.columnFuncSelector(order, p.id())));
                }, false), String.class));
        assertEquals(String.join(separator, "B", "B", "A", "A", "A"), selection.toList().get(0));
        assertFalse(selection.toSQL().contains(separator));
        assertEquals(selection.toSQL(), selection.cloneQueryable().toSQL());
        assertEquals(List.of("A", "A", "A", "B", "B"), Arrays.stream(rows()
                .selectColumn(p -> p.name().joining(",")).toList().get(0).split(",")).sorted().toList());
        assertNull(rows().where(p -> p.id().lt(0)).selectColumn(p -> p.name().joining(",")).toList().get(0));
        var nullable = query.queryable("SELECT 1 AS \"id\",CAST(NULL AS NVARCHAR(10)) AS \"name\" FROM DUMMY"
                + " UNION ALL SELECT 2, 'a' FROM DUMMY UNION ALL SELECT 3, '' FROM DUMMY", TestProduct.class);
        assertEquals("a,", nullable.selectColumn(p -> p.name().joining(",")).toList().get(0));
    }

    @Test
    void columnExtremaIgnoreNullValuesAndRetainRowCorrelation() {
        var selection = rows().orderBy(p -> p.id().asc()).select(p -> Select.DRAFT.of(
                p.expression().maxColumns(p.amount(), p.id().toNumber(BigDecimal.class)),
                p.expression().minColumns(p.amount(), p.id().toNumber(BigDecimal.class))));
        var result = selection.toList();
        var high = List.of("10", "10", "20", "4", "5");
        var low = List.of("1", "2", "3", "4", "5");
        for (int i = 0; i < result.size(); i++) {
            assertDecimal(high.get(i), result.get(i).getValue1());
            assertDecimal(low.get(i), result.get(i).getValue2());
        }
        assertNull(rows().where(p -> p.id().eq(4))
                .selectColumn(p -> p.expression().maxColumns(p.amount(), p.amount())).toList().get(0));
    }

    @Test
    void rankingAndAggregateWindowsUseExistingPartitionAndOrderApis() {
        var selection = rows().where(p -> p.name().eq("A")).orderBy(p -> p.id().asc())
                .select(p -> Select.DRAFT.of(p.expression().rowNumberOver().partitionBy(p.name()).orderBy(p.id()),
                        p.expression().rankOver().partitionBy(p.name()).orderBy(p.amount()),
                        p.expression().denseRankOver().partitionBy(p.name()).orderBy(p.amount()),
                        p.expression().sumOver(p.amount()).partitionBy(p.name()),
                        p.expression().countOver(p.amount()).partitionBy(p.name()),
                        p.expression().avgOver(p.amount()).partitionBy(p.name()),
                        p.expression().minOver(p.amount()).partitionBy(p.name()),
                        p.expression().maxOver(p.amount()).partitionBy(p.name())));
        assertEquals(selection.toSQL(), selection.cloneQueryable().toSQL());
        var result = selection.toList();
        for (int i = 0; i < result.size(); i++) {
            var row = result.get(i);
            assertEquals(i + 1L, row.getValue1());
            assertEquals(i == 2 ? 3L : 1L, row.getValue2());
            assertEquals(i == 2 ? 2L : 1L, row.getValue3());
            assertDecimal("40", row.getValue4());
            assertEquals(3L, row.getValue5());
            assertEquals(40.0 / 3, row.getValue6().doubleValue(), 0.000001);
            assertDecimal("10", row.getValue7());
            assertDecimal("20", row.getValue8());
        }
    }

    @Test
    void orderedWindowUsesTheDatabaseDefaultPeerFrame() {
        var sums = rows().where(p -> p.name().eq("A")).orderBy(p -> p.id().asc())
                .selectColumn(p -> p.expression().sumOver(p.amount()).partitionBy(p.name()).orderBy(p.amount())).toList();
        assertEquals(3, sums.size());
        assertDecimal("20", sums.get(0));
        assertDecimal("20", sums.get(1));
        assertDecimal("40", sums.get(2));
    }

    private EntityQueryable<TestProductProxy, TestProduct> rows() {
        return query.queryable("SELECT 1 AS \"id\",CAST(? AS NVARCHAR(10)) AS \"name\",CAST(? AS DECIMAL(19,4)) AS \"amount\" FROM DUMMY"
                        + " UNION ALL SELECT 2, ?, ? FROM DUMMY UNION ALL SELECT 3, ?, ? FROM DUMMY"
                        + " UNION ALL SELECT 4, ?, ? FROM DUMMY UNION ALL SELECT 5, ?, ? FROM DUMMY", TestProduct.class,
                Arrays.asList("A", BigDecimal.TEN, "A", BigDecimal.TEN, "A", new BigDecimal("20"), "B", null, "B", new BigDecimal("5")));
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
