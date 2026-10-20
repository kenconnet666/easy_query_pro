package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void sampleFilteringAndApproximateInputReuseTheExistingExpressionChain(DecimalDslTest.Backend backend) {
        var query = backend.client().queryable(TestProduct.class).select(p -> {
            var pro = ProDsl.of(p);
            return Select.DRAFT.of(pro.variancePopulation(p.amount()).filter(() -> p.id().le(2)),
                    pro.standardDeviationSample(p.amount()));
        });
        var result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("VAR_POP("), result.getSQL());
        assertTrue(result.getSQL().contains("STDDEV_SAMP("), result.getSQL());
        assertTrue(result.getSQL().contains("CASE WHEN"), result.getSQL());
        assertTrue(result.getSQL().contains(backend.dialect() == SqlDialect.POSTGRESQL ? "float8" : "AS DOUBLE"), result.getSQL());
        assertEquals(List.of(2), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void aggregateAndWindowInputsNeedAnExplicitRelationBoundary(DecimalDslTest.Backend backend) {
        var client = backend.client();
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p ->
                ProDsl.of(p).varianceSample(p.amount().sumBigDecimal())).toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p -> {
            var pro = ProDsl.of(p);
            return pro.variancePopulation(pro.over(p.amount().sumBigDecimal(), pro.window()));
        }).toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p -> {
            var pro = ProDsl.of(p);
            return pro.over(pro.nativeCast(pro.varianceSample(p.amount()), CommonTypes.decimal(19, 4)), pro.window());
        }).toSQL());
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void statisticsAndFiltersCannotHideAnAggregateInput(DecimalDslTest.Backend backend) {
        var client = backend.client();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p ->
                        ProDsl.of(p).variancePopulation(ProDsl.of(p).varianceSample(p.amount()))).toSQL()),
                () -> assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p ->
                        ProDsl.of(p).variancePopulation(p.amount().sumBigDecimal()).filter(() -> p.id().gt(0))).toSQL()));
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void statisticsCannotUseOrdinaryShardAggregateMerging(DecimalDslTest.Backend backend) {
        var query = backend.client().queryable(TestProduct.class).selectColumn(p -> {
            p.getEntitySQLContext().getExpressionContext().useSharding();
            return ProDsl.of(p).variancePopulation(p.amount()).multiply(100);
        });
        assertThrows(UnsupportedOperationException.class, query::toSQL);
    }
}
