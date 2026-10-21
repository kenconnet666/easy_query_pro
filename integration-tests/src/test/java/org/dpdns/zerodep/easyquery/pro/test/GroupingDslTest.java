package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProGroups;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.group.GroupingSet;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaGroups;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgGroups;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.grouping.proxy.Grouping2Proxy;
import com.easy.query.core.proxy.columns.types.SQLIntegerTypeColumn;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import com.easy.query.core.proxy.columns.types.SQLBigDecimalTypeColumn;
import java.math.BigDecimal;
import com.easy.query.core.proxy.grouping.Grouping2;
import com.easy.query.core.proxy.core.draft.proxy.Draft4Proxy;
import com.easy.query.core.proxy.core.draft.Draft4;
import com.easy.query.core.proxy.grouping.proxy.Grouping1Proxy;
import com.easy.query.core.proxy.grouping.Grouping1;
import java.util.Arrays;
import com.easy.query.core.proxy.core.draft.proxy.Draft2Proxy;
import com.easy.query.core.proxy.core.draft.Draft2;
import com.easy.query.core.proxy.core.draft.proxy.Draft1Proxy;
import com.easy.query.core.proxy.core.draft.Draft1;
import com.easy.query.core.proxy.columns.types.SQLAnyTypeColumn;

import static org.junit.jupiter.api.Assertions.*;

class GroupingDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void rollupKeepsTheOrdinaryBranchAndExplicitProjection(DecimalDslTest.Backend backend) {
        EntityQueryable<Grouping2Proxy<SQLIntegerTypeColumn<TestProductProxy>, Integer, SQLBigDecimalTypeColumn<TestProductProxy>, BigDecimal, TestProductProxy>, Grouping2<Integer, BigDecimal>> grouped = backend.client().queryable(TestProduct.class).groupBy(p -> GroupKeys.of(p.id(), p.amount()));
        EntityQueryable<Draft4Proxy<Integer, BigDecimal, Long, Integer>, Draft4<Integer, BigDecimal, Long, Integer>> result = ProGroups.rollup(grouped, g -> Select.DRAFT.of(g.key1(), g.key2(), g.count(), ProGroups.mask(g, g.key1(), g.key2())));
        String sql = result.cloneQueryable().toSQL();
        assertTrue(sql.contains("GROUP BY ROLLUP("), sql);
        assertTrue(sql.contains(backend.dialect() == SqlDialect.HANA ? "GROUPING_ID(" : "GROUPING("), sql);
        assertFalse(sql.substring(0, sql.indexOf(" FROM ")).contains("ROLLUP"), sql);
        assertEquals(sql, result.cloneQueryable().toSQL());
        String ordinary = grouped.select(g -> Select.DRAFT.of(g.key1(), g.count())).toSQL();
        assertFalse(ordinary.contains("ROLLUP"), ordinary);
        assertFalse(ordinary.contains("HAVING"), ordinary);
        EntityQueryable<Grouping1Proxy<SQLIntegerTypeColumn<TestProductProxy>, Integer, TestProductProxy>, Grouping1<Integer>> fresh = backend.client().queryable(TestProduct.class).groupBy(p -> GroupKeys.of(p.id()));
        assertThrows(IllegalArgumentException.class, () -> ProGroups.rollup(fresh, g -> g));
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void groupingSetsKeepKeysTypedAndRejectWrongDialectOrMembers(DecimalDslTest.Backend backend) {
        EntityQueryable<Grouping2Proxy<SQLIntegerTypeColumn<TestProductProxy>, Integer, SQLBigDecimalTypeColumn<TestProductProxy>, BigDecimal, TestProductProxy>, Grouping2<Integer, BigDecimal>> grouped = backend.client().queryable(TestProduct.class).groupBy(p -> GroupKeys.of(p.id(), p.amount()));
        if (backend.dialect() == SqlDialect.MYSQL) {
            assertThrows(IllegalArgumentException.class, () -> PgGroups.groupingSets(grouped,
                    g -> Arrays.asList(GroupingSet.of(g.key1())), g -> Select.DRAFT.of(g.key1(), g.count())));
            return;
        }
        EntityQueryable<Draft2Proxy<Integer, Long>, Draft2<Integer, Long>> result = backend.dialect() == SqlDialect.POSTGRESQL
                ? PgGroups.groupingSets(grouped, g -> Arrays.asList(GroupingSet.of(g.key1()), GroupingSet.total()),
                g -> Select.DRAFT.of(g.key1(), g.count()))
                : HanaGroups.groupingSets(grouped, g -> Arrays.asList(GroupingSet.of(g.key1()), GroupingSet.total()),
                g -> Select.DRAFT.of(g.key1(), g.count()));
        assertTrue(result.toSQL().contains("GROUP BY GROUPING SETS ("), result.toSQL());
        assertThrows(IllegalArgumentException.class, () -> PgGroups.groupingSets(grouped,
                g -> Arrays.asList(GroupingSet.of(g.key1()), GroupingSet.of(g.key1())), g -> Select.DRAFT.of(g.key1())));
        assertThrows(IllegalArgumentException.class, () -> ProGroups.rollup(grouped,
                g -> Select.DRAFT.of(ProGroups.mask(g, g.key1(), g.key1()))));
        assertThrows(IllegalArgumentException.class, () -> ProGroups.rollup(grouped,
                g -> Select.DRAFT.of(ProGroups.mask(g, g.groupTable().id()))));
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void rollupCannotUseShardAggregateMerging(DecimalDslTest.Backend backend) {
        EntityQueryable<Grouping1Proxy<SQLIntegerTypeColumn<TestProductProxy>, Integer, TestProductProxy>, Grouping1<Integer>> grouped = backend.client().queryable(TestProduct.class).groupBy(p -> GroupKeys.of(p.id()));
        grouped.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        EntityQueryable<Draft2Proxy<Integer, Long>, Draft2<Integer, Long>> result = ProGroups.rollup(grouped, g -> Select.DRAFT.of(g.key1(), g.count()));
        assertThrows(UnsupportedOperationException.class, result::toSQL);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void masksAndProjectionOwnershipHaveExplicitBoundaries(DecimalDslTest.Backend backend) {
        EntityQueryable<Grouping1Proxy<SQLIntegerTypeColumn<TestProductProxy>, Integer, TestProductProxy>, Grouping1<Integer>> grouped = backend.client().queryable(TestProduct.class).groupBy(p -> GroupKeys.of(p.id()));
        assertThrows(IllegalArgumentException.class, () -> ProGroups.rollup(grouped,
                g -> Select.DRAFT.of(ProGroups.mask(g))));
        assertThrows(IllegalArgumentException.class, () -> ProGroups.rollup(grouped,
                g -> Select.DRAFT.of(ProGroups.mask(g, Collections.nCopies(32, g.key1()).toArray(new PropTypeColumn<?>[0])))));
        assertThrows(IllegalArgumentException.class, () -> ProGroups.rollup(grouped,
                g -> Select.DRAFT.of(ProGroups.mask(g, g.groupTable().id()))));
        EntityQueryable<Draft1Proxy<Integer>, Draft1<Integer>> source = backend.client().queryable(TestProduct.class).select(p -> Select.DRAFT.of(p.id()));
        EntityQueryable<Grouping1Proxy<SQLAnyTypeColumn<Draft1Proxy<Integer>, Integer>, Integer, Draft1Proxy<Integer>>, Grouping1<Integer>> draftGroups = source.groupBy(p -> GroupKeys.of(p.value1()));
        assertThrows(IllegalArgumentException.class, () -> ProGroups.rollup(draftGroups, g -> g.groupTable()));
    }
}
