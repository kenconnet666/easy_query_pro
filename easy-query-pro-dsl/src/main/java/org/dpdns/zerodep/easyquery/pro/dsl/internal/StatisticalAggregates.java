package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.filter.NumberFilterTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.filter.impl.NumberFilterTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

import java.util.Objects;

/**
 * Shared approximate statistics; the existing FILTER expression owns sample selection.
 */
public final class StatisticalAggregates {
    public enum Kind {VAR_POP, VAR_SAMP, STDDEV_POP, STDDEV_SAMP}

    private StatisticalAggregates() {
    }

    public static NumberFilterTypeExpression<Double> create(ExpressionScope scope, PropTypeColumn<? extends Number> source, Kind kind) {
        scope.requireSource(source);
        Objects.requireNonNull(kind, "kind");
        // FILTER replaces the sample with CASE, which no longer exposes the original aggregate classification.
        requireSample(scope, scope.operand(source, scope.runtime().fx()));
        return new NumberFilterTypeExpressionImpl<>(scope.context(), source, source.getTable(), null, (sample, factory) -> {
            var input = scope.operand(sample, factory);
            requireSample(scope, input);
            // HANA DECIMAL statistics can truncate to the input scale. Cast before aggregation, not afterwards.
            var approximate = scope.dialect() == SqlDialect.MYSQL
                    ? factory.anySQLFunction("CAST({0} AS DOUBLE)", args -> args.sqlFunc(input))
                    : factory.cast(input, Double.class);
            return scope.aggregate(factory.anySQLFunction(kind.name() + "({0})", args -> args.sqlFunc(approximate)), source.getTable());
        }, Double.class);
    }

    private static void requireSample(ExpressionScope scope, SQLFunction input) {
        if (input.getAggregationType() != AggregationType.UNKNOWN || ExpressionScope.isWindowAggregate(input) || scope.hasWindow(input))
            throw new IllegalArgumentException("Project aggregate and window results before using them as statistical samples");
    }
}
