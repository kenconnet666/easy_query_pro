package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NullSafeDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void composesBooleanValuesWithoutDuplicatingParametersOrAppendingPredicates(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var type = CommonTypes.decimal(19, 2);
        var untouched = client.queryable(TestProduct.class).where(p -> ProDsl.of(p).nullSafeEquals(p.name(), p.name()));
        assertFalse(untouched.toSQL().contains(" WHERE "));
        var query = client.queryable(TestProduct.class).selectColumn(p -> {
            var sql = ProDsl.of(p);
            return sql.nullSafeEquals(sql.value(new BigDecimal("19.99"), type).multiply(100), sql.value(new BigDecimal("1999"), type));
        });
        var rendered = query.toSQLResult();
        assertEquals(List.of(new BigDecimal("19.99"), 100, new BigDecimal("1999.00")),
                rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(rendered.getSQL(), query.cloneQueryable().toSQL());
        var text = client.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p)
                .isDistinctFrom(p.name(), p.expression().constant("O'Reilly 😀"))).toSQLResult();
        assertFalse(text.getSQL().contains("O'Reilly"));
        assertEquals(List.of("O'Reilly 😀"), text.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void comparisonGuardsBothQueryOperandsAndClientOwnership(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var result = new AtomicReference<BooleanTypeExpression<Boolean>>();
        client.queryable(TestProduct.class).where(p -> result.set(ProDsl.of(p).nullSafeEquals(p.name(), p.name())));
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p -> result.get()).toSQL());
        assertThrows(IllegalArgumentException.class, () -> backend.client().queryable(TestProduct.class).selectColumn(p -> result.get()).toSQL());
    }
}
