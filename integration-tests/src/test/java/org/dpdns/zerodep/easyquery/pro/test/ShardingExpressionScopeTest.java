package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ShardingExpressionScopeTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void clonedWindowsRejectShardingAddedToTheOuterQuery(DecimalDslTest.Backend backend) {
        var original = backend.client().queryable(TestProduct.class).select(p -> {
            var pro = ProDsl.of(p);
            return Select.DRAFT.of(p.id(), pro.over(p.amount().sumBigDecimal(), pro.window()));
        });
        var window = original.cloneQueryable();
        window.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, window::toSQL);
        assertDoesNotThrow(() -> original.toSQL(), "A rejected clone must not mark the original query as sharded");
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void lateShardingInspectsCteUnionAndScalarSubquerySources(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var first = client.queryable(TestProduct.class).select(p -> Select.DRAFT.of(
                ProDsl.of(p).over(p.amount().sumBigDecimal(), ProDsl.of(p).window())));
        var second = client.queryable(TestProduct.class).select(p -> Select.DRAFT.of(
                ProDsl.of(p).over(p.amount().sumBigDecimal(), ProDsl.of(p).window())));
        var cte = first.cloneQueryable().toCteAs("window_source").cloneQueryable();
        cte.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, cte::toSQL);
        var union = first.cloneQueryable().unionAll(second).cloneQueryable();
        union.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, union::toSQL);
        var scalar = client.getEasyQueryClient().queryable(TestProduct.class).select(BigDecimal.class,
                s -> s.sqlNativeSegment("{0}", c -> c.expression(first.getClientQueryable().limit(1)))).cloneQueryable();
        scalar.getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, scalar::toSQL);
        var joined = client.getEasyQueryClient().queryable(TestProduct.class)
                .leftJoin(client.getEasyQueryClient().queryable(TestProduct.class).select(s -> s.columnAll()),
                        (left, right) -> left.sqlNativeSegment("{0} > 0", c -> c.expression(first.getClientQueryable().limit(1))))
                .cloneQueryable();
        joined.getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, joined::toSQL);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void removedWindowProjectionsDoNotLeaveAStickyShardingRestriction(DecimalDslTest.Backend backend) {
        var projected = backend.client().queryable(TestProduct.class).select(p -> Select.DRAFT.of(
                ProDsl.of(p).over(p.amount().sumBigDecimal(), ProDsl.of(p).window())));
        var count = projected.cloneQueryable().selectCount(Long.class);
        count.getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertFalse(count.toSQL().contains("OVER"));
        assertDoesNotThrow(() -> count.toSQL());
        var scalar = backend.client().queryable(TestProduct.class).select(p -> Select.DRAFT.of(
                ProDsl.of(p).nativeCast(p.amount(), CommonTypes.decimal(19, 2)))).cloneQueryable();
        scalar.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertDoesNotThrow(() -> scalar.toSQL());
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void windowsAndTheirScalarWrappersRequireCompleteDatabasePartitions(DecimalDslTest.Backend backend) {
        var window = backend.client().queryable(TestProduct.class).select(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            var pro = ProDsl.of(p);
            var sum = pro.over(p.amount().sumBigDecimal(), pro.window().partitionBy(p.name()));
            return Select.DRAFT.of(pro.nativeCast(sum, CommonTypes.decimal(19, 2)), pro.nullIfValue(sum, BigDecimal.ZERO));
        });
        assertThrows(UnsupportedOperationException.class, window::toSQL);
        assertThrows(UnsupportedOperationException.class, () -> window.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void knownScalarCompositionRetainsItsRowLocalGuarantee(DecimalDslTest.Backend backend) {
        var query = backend.client().queryable(TestProduct.class).select(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            var d = ProDsl.of(p);
            var value = d.nativeCast(p.amount(), CommonTypes.decimal(19, 2));
            return Select.DRAFT.of(d.nativeCast(value, CommonTypes.decimal(19, 1)), d.nullIfValue(value, BigDecimal.ZERO),
                    d.nullSafeEqualsValue(p.name(), null), d.nullSafeEquals(value, d.nullValue(CommonTypes.decimal(19, 2))));
        });
        assertEquals(query.toSQL(), query.cloneQueryable().toSQL());
        assertTrue(query.toSQL().contains("NULLIF"));
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void aggregatesAndOpaqueWrappersCannotBeMistakenForRowLocalExpressions(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var aggregate = client.queryable(TestProduct.class).selectColumn(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            return ProDsl.of(p).nativeCast(p.amount().sumBigDecimal(), CommonTypes.decimal(19, 1));
        });
        assertThrows(UnsupportedOperationException.class, aggregate::toSQL);
        var hidden = client.queryable(TestProduct.class).selectColumn(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            return ProDsl.of(p).nativeCast(p.amount().sumBigDecimal().add(1), CommonTypes.decimal(19, 1));
        });
        assertThrows(UnsupportedOperationException.class, hidden::toSQL);
        // 3.2.17 exposes no public operand metadata for arbitrary scalar wrappers either.
        var opaque = client.queryable(TestProduct.class).selectColumn(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            return ProDsl.of(p).nativeCast(p.amount().add(1), CommonTypes.decimal(19, 1));
        });
        assertThrows(UnsupportedOperationException.class, opaque::toSQL);
    }
}
