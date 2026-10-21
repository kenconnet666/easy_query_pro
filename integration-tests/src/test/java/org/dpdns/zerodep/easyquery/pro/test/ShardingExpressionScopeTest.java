package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft2Proxy;
import com.easy.query.core.proxy.core.draft.Draft2;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.proxy.core.draft.proxy.Draft1Proxy;
import com.easy.query.core.proxy.core.draft.Draft1;
import com.easy.query.core.basic.api.select.ClientQueryable;
import com.easy.query.core.basic.api.select.ClientQueryable2;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.core.draft.proxy.Draft4Proxy;
import com.easy.query.core.proxy.core.draft.Draft4;

import static org.junit.jupiter.api.Assertions.*;

class ShardingExpressionScopeTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void clonedWindowsRejectShardingAddedToTheOuterQuery(DecimalDslTest.Backend backend) {
        EntityQueryable<Draft2Proxy<Integer, BigDecimal>, Draft2<Integer, BigDecimal>> original = backend.client().queryable(TestProduct.class).select(p -> {
            ProDsl pro = ProDsl.of(p);
            return Select.DRAFT.of(p.id(), pro.over(p.amount().sumBigDecimal(), pro.window()));
        });
        EntityQueryable<Draft2Proxy<Integer, BigDecimal>, Draft2<Integer, BigDecimal>> window = original.cloneQueryable();
        window.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, window::toSQL);
        assertDoesNotThrow(() -> original.toSQL(), "A rejected clone must not mark the original query as sharded");
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void lateShardingInspectsCteUnionAndScalarSubquerySources(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        EntityQueryable<Draft1Proxy<BigDecimal>, Draft1<BigDecimal>> first = client.queryable(TestProduct.class).select(p -> Select.DRAFT.of(
                ProDsl.of(p).over(p.amount().sumBigDecimal(), ProDsl.of(p).window())));
        EntityQueryable<Draft1Proxy<BigDecimal>, Draft1<BigDecimal>> second = client.queryable(TestProduct.class).select(p -> Select.DRAFT.of(
                ProDsl.of(p).over(p.amount().sumBigDecimal(), ProDsl.of(p).window())));
        EntityQueryable<Draft1Proxy<BigDecimal>, Draft1<BigDecimal>> cte = first.cloneQueryable().toCteAs("window_source").cloneQueryable();
        cte.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, cte::toSQL);
        EntityQueryable<Draft1Proxy<BigDecimal>, Draft1<BigDecimal>> union = first.cloneQueryable().unionAll(second).cloneQueryable();
        union.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, union::toSQL);
        ClientQueryable<BigDecimal> scalar = client.getEasyQueryClient().queryable(TestProduct.class).select(BigDecimal.class,
                s -> s.sqlNativeSegment("{0}", c -> c.expression(first.getClientQueryable().limit(1)))).cloneQueryable();
        scalar.getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, scalar::toSQL);
        ClientQueryable2<TestProduct, TestProduct> joined = client.getEasyQueryClient().queryable(TestProduct.class)
                .leftJoin(client.getEasyQueryClient().queryable(TestProduct.class).select(s -> s.columnAll()),
                        (left, right) -> left.sqlNativeSegment("{0} > 0", c -> c.expression(first.getClientQueryable().limit(1))))
                .cloneQueryable();
        joined.getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, joined::toSQL);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void removedWindowProjectionsDoNotLeaveAStickyShardingRestriction(DecimalDslTest.Backend backend) {
        EntityQueryable<Draft1Proxy<BigDecimal>, Draft1<BigDecimal>> projected = backend.client().queryable(TestProduct.class).select(p -> Select.DRAFT.of(
                ProDsl.of(p).over(p.amount().sumBigDecimal(), ProDsl.of(p).window())));
        Query<Long> count = projected.cloneQueryable().selectCount(Long.class);
        count.getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertFalse(count.toSQL().contains("OVER"));
        assertDoesNotThrow(() -> count.toSQL());
        EntityQueryable<Draft1Proxy<BigDecimal>, Draft1<BigDecimal>> scalar = backend.client().queryable(TestProduct.class).select(p -> Select.DRAFT.of(
                ProDsl.of(p).nativeCast(p.amount(), CommonTypes.decimal(19, 2)))).cloneQueryable();
        scalar.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertDoesNotThrow(() -> scalar.toSQL());
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void windowsAndTheirScalarWrappersRequireCompleteDatabasePartitions(DecimalDslTest.Backend backend) {
        EntityQueryable<Draft2Proxy<BigDecimal, BigDecimal>, Draft2<BigDecimal, BigDecimal>> window = backend.client().queryable(TestProduct.class).select(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            ProDsl pro = ProDsl.of(p);
            NumberTypeExpression<BigDecimal> sum = pro.over(p.amount().sumBigDecimal(), pro.window().partitionBy(p.name()));
            return Select.DRAFT.of(pro.nativeCast(sum, CommonTypes.decimal(19, 2)), pro.nullIfValue(sum, BigDecimal.ZERO));
        });
        assertThrows(UnsupportedOperationException.class, window::toSQL);
        assertThrows(UnsupportedOperationException.class, () -> window.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void knownScalarCompositionRetainsItsRowLocalGuarantee(DecimalDslTest.Backend backend) {
        EntityQueryable<Draft4Proxy<BigDecimal, BigDecimal, Boolean, Boolean>, Draft4<BigDecimal, BigDecimal, Boolean, Boolean>> query = backend.client().queryable(TestProduct.class).select(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            ProDsl d = ProDsl.of(p);
            NumberTypeExpression<BigDecimal> value = d.nativeCast(p.amount(), CommonTypes.decimal(19, 2));
            return Select.DRAFT.of(d.nativeCast(value, CommonTypes.decimal(19, 1)), d.nullIfValue(value, BigDecimal.ZERO),
                    d.nullSafeEqualsValue(p.name(), null), d.nullSafeEquals(value, d.nullValue(CommonTypes.decimal(19, 2))));
        });
        assertEquals(query.toSQL(), query.cloneQueryable().toSQL());
        assertTrue(query.toSQL().contains("NULLIF"));
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void aggregatesAndOpaqueWrappersCannotBeMistakenForRowLocalExpressions(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        Query<BigDecimal> aggregate = client.queryable(TestProduct.class).selectColumn(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            return ProDsl.of(p).nativeCast(p.amount().sumBigDecimal(), CommonTypes.decimal(19, 1));
        });
        assertThrows(UnsupportedOperationException.class, aggregate::toSQL);
        Query<BigDecimal> hidden = client.queryable(TestProduct.class).selectColumn(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            return ProDsl.of(p).nativeCast(p.amount().sumBigDecimal().add(1), CommonTypes.decimal(19, 1));
        });
        assertThrows(UnsupportedOperationException.class, hidden::toSQL);
        // 3.2.17 exposes no public operand metadata for arbitrary scalar wrappers either.
        Query<BigDecimal> opaque = client.queryable(TestProduct.class).selectColumn(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            return ProDsl.of(p).nativeCast(p.amount().add(1), CommonTypes.decimal(19, 1));
        });
        assertThrows(UnsupportedOperationException.class, opaque::toSQL);
    }
}
