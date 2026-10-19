package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NullIfDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void retainsTypedOperatorsBindingsAndClone(DecimalDslTest.Backend backend) {
        var query = backend.client().queryable(TestProduct.class)
                .where(p -> ProDsl.of(p).nullIfValue(p.amount(), BigDecimal.ZERO).multiply(10).gt(BigDecimal.ONE))
                .orderBy(p -> ProDsl.of(p).nullIfValue(p.name(), "sort").asc())
                .selectColumn(p -> ProDsl.of(p).nullIfValue(p.name(), "O'Reilly 😀").toUpper());
        var result = query.toSQLResult();
        assertTrue(result.getSQL().contains("UPPER(NULLIF("), result.getSQL());
        assertEquals(List.of("O'Reilly 😀", BigDecimal.ZERO, 10, BigDecimal.ONE, "sort"),
                result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertFalse(result.getSQL().contains("O'Reilly"));
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void refusesUnrelatedExpressionReuse(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var expression = new AtomicReference<StringTypeExpression<String>>();
        client.queryable(TestProduct.class).where(p -> expression.set(ProDsl.of(p).nullIfValue(p.name(), "hidden")));
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p -> expression.get()).toSQL());
        assertThrows(IllegalArgumentException.class, () -> backend.client().queryable(TestProduct.class).selectColumn(p -> expression.get()).toSQL());
    }
}
