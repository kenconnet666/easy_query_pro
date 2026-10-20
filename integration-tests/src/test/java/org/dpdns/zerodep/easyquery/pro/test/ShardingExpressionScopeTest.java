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
