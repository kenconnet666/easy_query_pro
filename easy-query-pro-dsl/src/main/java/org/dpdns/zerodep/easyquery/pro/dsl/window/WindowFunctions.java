package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.api.proxy.extension.window.FirstValueOffset;
import com.easy.query.api.proxy.extension.window.LastValueOffset;
import com.easy.query.api.proxy.extension.window.NextSelector;
import com.easy.query.api.proxy.extension.window.NthValueOffset;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.ColumnObjectFunctionAvailable;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

import java.util.Objects;
import java.util.function.Function;

/**
 * Adapts upstream aggregates and value selectors without rewriting their generated SQL.
 */
public final class WindowFunctions {
    private WindowFunctions() {
    }

    public static <T, C extends PropTypeColumn<T>> C aggregate(ExpressionScope scope, ColumnObjectFunctionAvailable<T, C> aggregate, WindowSpec<?> spec) {
        var function = aggregateFunction(scope, aggregate, spec);
        return aggregate.createChainExpression(function, EasyClassUtil.getObjectTypeWhenPrimitive(aggregate.getPropertyType()));
    }

    public static <T, E extends PropTypeColumn<T>> E aggregate(ExpressionScope scope, PropTypeColumn<T> aggregate,
                                                               WindowSpec<?> spec, ExpressionFactory<T, ? extends E> factory) {
        var function = aggregateFunction(scope, aggregate, spec);
        return Objects.requireNonNull(Objects.requireNonNull(factory, "factory").create(scope, aggregate, function), "factory result");
    }

    private static Function<SQLFunc, SQLFunction> aggregateFunction(ExpressionScope scope, PropTypeColumn<?> source, WindowSpec<?> spec) {
        scope.requireSource(source);
        Objects.requireNonNull(spec, "spec").validate(scope, false);
        if (!(source instanceof DSLSQLFunctionAvailable function))
            throw new IllegalArgumentException("OVER requires an existing SUM, AVG, COUNT, MIN or MAX expression");
        var aggregate = function.func().apply(scope.runtime().fx());
        if (!ExpressionScope.isWindowAggregate(aggregate))
            throw new IllegalArgumentException("OVER requires a non-distinct bare aggregate; apply scalar wrappers after OVER");
        if (scope.hasWindow(aggregate))
            throw new IllegalArgumentException("Project a window into a derived relation before applying another window");
        return factory -> scope.window(spec.apply(factory, aggregate), source.getTable());
    }

    public static <T, C extends PropTypeColumn<T>> NextSelector<T, C> value(ExpressionScope scope, ColumnObjectFunctionAvailable<T, C> source, WindowSpec<?> spec) {
        return value(scope, source, spec, (bound, mapping, function) -> source.createChainExpression(function, EasyClassUtil.getObjectTypeWhenPrimitive(source.getPropertyType())));
    }

    public static <T, E extends PropTypeColumn<T>> NextSelector<T, E> value(ExpressionScope scope, PropTypeColumn<T> source,
                                                                            WindowSpec<?> spec, ExpressionFactory<T, ? extends E> factory) {
        scope.requireSource(source);
        Objects.requireNonNull(spec, "spec").validate(scope, true);
        Objects.requireNonNull(factory, "factory");
        return new NextSelector<>(offset -> {
            String name;
            if (offset instanceof FirstValueOffset<?>) name = "FIRST_VALUE";
            else if (offset instanceof LastValueOffset<?>) name = "LAST_VALUE";
            else if (offset instanceof NthValueOffset<?>) {
                if (offset.getOffset() < 1)
                    throw new IllegalArgumentException("NTH_VALUE requires a positive position");
                name = "NTH_VALUE";
            } else
                throw new IllegalArgumentException("Use the upstream offset API for LAG/LEAD; these functions do not use a frame");
            String template = name + "({0}" + (offset instanceof NthValueOffset<?> ? "," + offset.getOffset() : "") + ")";
            Function<SQLFunc, SQLFunction> expression = sql -> {
                var operand = scope.operand(source, sql);
                if (!ExpressionScope.isRowLocal(operand))
                    throw new IllegalArgumentException("Project aggregates, windows and unclassified expressions into a derived relation before applying a value window");
                var value = sql.anySQLFunction(template, args -> args.sqlFunc(operand));
                return scope.window(spec.apply(sql, value), source.getTable());
            };
            return Objects.requireNonNull(factory.create(scope, source, expression), "factory result");
        });
    }
}
