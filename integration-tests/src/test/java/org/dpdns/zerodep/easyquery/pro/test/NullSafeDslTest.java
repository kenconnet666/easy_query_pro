package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.common.ToSQLResult;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class NullSafeDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void composesBooleanValuesWithoutDuplicatingParametersOrAppendingPredicates(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        NumericType<BigDecimal> type = CommonTypes.decimal(19, 2);
        EntityQueryable<TestProductProxy, TestProduct> untouched = client.queryable(TestProduct.class).where(p -> ProDsl.of(p).nullSafeEquals(p.name(), p.name()));
        assertFalse(untouched.toSQL().contains(" WHERE "));
        Query<Boolean> query = client.queryable(TestProduct.class).selectColumn(p -> {
            ProDsl sql = ProDsl.of(p);
            return sql.nullSafeEquals(sql.value(new BigDecimal("19.99"), type).multiply(100), sql.value(new BigDecimal("1999"), type));
        });
        ToSQLResult rendered = query.toSQLResult();
        assertEquals(Arrays.asList(new BigDecimal("19.99"), 100, new BigDecimal("1999.00")),
                rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(rendered.getSQL(), query.cloneQueryable().toSQL());
        ToSQLResult text = client.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p)
                .isDistinctFrom(p.name(), p.expression().constant("O'Reilly 😀"))).toSQLResult();
        assertFalse(text.getSQL().contains("O'Reilly"));
        assertEquals(Arrays.asList("O'Reilly 😀"), text.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void comparisonGuardsBothQueryOperandsAndClientOwnership(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        AtomicReference<BooleanTypeExpression<Boolean>> result = new AtomicReference<BooleanTypeExpression<Boolean>>();
        client.queryable(TestProduct.class).where(p -> result.set(ProDsl.of(p).nullSafeEquals(p.name(), p.name())));
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p -> result.get()).toSQL());
        assertThrows(IllegalArgumentException.class, () -> backend.client().queryable(TestProduct.class).selectColumn(p -> result.get()).toSQL());
    }
}
