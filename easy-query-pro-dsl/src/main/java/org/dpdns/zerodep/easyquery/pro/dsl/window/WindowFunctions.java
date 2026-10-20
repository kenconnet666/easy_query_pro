package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.api.proxy.extension.window.FirstValueOffset;
import com.easy.query.api.proxy.extension.window.LastValueOffset;
import com.easy.query.api.proxy.extension.window.NextSelector;
import com.easy.query.api.proxy.extension.window.NthValueOffset;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.ColumnObjectFunctionAvailable;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

import java.util.Objects;
import java.util.function.Function;

/**
 * 将聚合、值与排名函数挂到共同窗口规格，复用上游表达式返回类型族。
 *
 * <p>聚合窗口接收单个非 DISTINCT 裸聚合。应先应用 OVER，再追加 CAST/NULLIF 等标量操作；
 * CAST(SUM(...)) 不能作为可直接接 OVER 的函数。同层窗口先投影到派生关系后才能继续作为窗口输入。
 *
 * <p>值窗口要求明确排序，以及已知逐行安全的输入。无法识别的上游复合表达式先投影成普通列，
 * 避免把隐藏聚合或窗口的 UNKNOWN 函数错误标记为保持原行数。
 */
public final class WindowFunctions {
    private WindowFunctions() {
    }

    public static NumberTypeExpression<Long> rank(ExpressionScope scope, WindowSpec<?> spec) {
        return ranking(scope, spec, "RANK()");
    }

    public static NumberTypeExpression<Long> denseRank(ExpressionScope scope, WindowSpec<?> spec) {
        return ranking(scope, spec, "DENSE_RANK()");
    }

    public static NumberTypeExpression<Long> rowNumber(ExpressionScope scope, WindowSpec<?> spec) {
        return ranking(scope, spec, "ROW_NUMBER()");
    }

    private static NumberTypeExpression<Long> ranking(ExpressionScope scope, WindowSpec<?> spec, String function) {
        Objects.requireNonNull(spec, "spec");
        // 排名由整个分区的排序决定；接受一个被数据库忽略的 frame 会误导调用方。
        if (spec.state.frame() != null)
            throw new IllegalArgumentException("Ranking windows do not accept a frame");
        spec.validate(scope, true);
        return new NumberTypeExpressionImpl<>(scope.context(), scope.anchor(), null, factory ->
                scope.window(spec.apply(factory, factory.anySQLFunction(function, ignored -> {
                })), scope.anchor()), Long.class);
    }

    public static <T, C extends PropTypeColumn<T>> C aggregate(ExpressionScope scope, ColumnObjectFunctionAvailable<T, C> aggregate, WindowSpec<?> spec) {
        var function = aggregateFunction(scope, aggregate, spec);
        return aggregate.createChainExpression(function, EasyClassUtil.getObjectTypeWhenPrimitive(aggregate.getPropertyType()));
    }

    /**
     * 工厂重载保留具体表达式 E；工厂必须沿用传入的 scope、映射来源和函数，不能返回其他查询的缓存对象。
     */
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

    /**
     * 复用 NextSelector 的 FIRST/LAST/NTH 选择接口。空 frame 或位置不存在时结果为 NULL，
     * 因此 primitive 载体需要装箱，投影读取器也必须保留可空语义。
     * LAG/LEAD 不使用 frame，NextSelector 上存在 next/prev 方法并不表示本入口支持它们。
     */
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
