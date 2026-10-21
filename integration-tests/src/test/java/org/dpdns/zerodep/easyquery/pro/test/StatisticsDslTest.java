package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft2Proxy;
import com.easy.query.core.proxy.core.draft.Draft2;
import com.easy.query.core.common.ToSQLResult;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.api.select.Query;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void sampleFilteringAndApproximateInputReuseTheExistingExpressionChain(DecimalDslTest.Backend backend) {
        EntityQueryable<Draft2Proxy<Double, Double>, Draft2<Double, Double>> query = backend.client().queryable(TestProduct.class).select(p -> {
            ProDsl pro = ProDsl.of(p);
            return Select.DRAFT.of(pro.variancePopulation(p.amount()).filter(() -> p.id().le(2)),
                    pro.standardDeviationSample(p.amount()));
        });
        ToSQLResult result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("VAR_POP("), result.getSQL());
        assertTrue(result.getSQL().contains("STDDEV_SAMP("), result.getSQL());
        assertTrue(result.getSQL().contains("CASE WHEN"), result.getSQL());
        assertTrue(result.getSQL().contains(backend.dialect() == SqlDialect.POSTGRESQL ? "float8" : "AS DOUBLE"), result.getSQL());
        assertEquals(Arrays.asList(2), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void aggregateAndWindowInputsNeedAnExplicitRelationBoundary(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p ->
                ProDsl.of(p).varianceSample(p.amount().sumBigDecimal())).toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p -> {
            ProDsl pro = ProDsl.of(p);
            return pro.variancePopulation(pro.over(p.amount().sumBigDecimal(), pro.window()));
        }).toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p -> {
            ProDsl pro = ProDsl.of(p);
            return pro.over(pro.nativeCast(pro.varianceSample(p.amount()), CommonTypes.decimal(19, 4)), pro.window());
        }).toSQL());
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void statisticsAndFiltersCannotHideAnAggregateInput(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p ->
                        ProDsl.of(p).variancePopulation(ProDsl.of(p).varianceSample(p.amount()))).toSQL()),
                () -> assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p ->
                        ProDsl.of(p).variancePopulation(p.amount().sumBigDecimal()).filter(() -> p.id().gt(0))).toSQL()));
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void statisticsCannotUseOrdinaryShardAggregateMerging(DecimalDslTest.Backend backend) {
        Query<BigDecimal> query = backend.client().queryable(TestProduct.class).selectColumn(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            return ProDsl.of(p).variancePopulation(p.amount()).multiply(100);
        });
        assertThrows(UnsupportedOperationException.class, query::toSQL);
    }
}
