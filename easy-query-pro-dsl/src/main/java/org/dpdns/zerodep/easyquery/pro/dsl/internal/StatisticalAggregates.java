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
 * 统一近似统计的输入精度和结果类型，样本选择继续复用上游 FILTER/CASE。
 *
 * <p>四个函数都在聚合前转换为 DOUBLE，结果为可空 Double。不能先用 HANA 的低 scale DECIMAL
 * 完成统计再转换，否则计算中丢失的小数无法恢复。这里不改变实体存储类型，也不提供任意精确统计。
 *
 * <p>必须使用 NumberFilterTypeExpression 的 ELSE NULL，不能换成 SUM 专用的 ELSE 0：零本身也是
 * 样本，会改变方差、标准差及有效样本数。空集、单样本与原生算术错误按明确的 POP/SAMP 契约处理。
 */
public final class StatisticalAggregates {
    public enum Kind {VAR_POP, VAR_SAMP, STDDEV_POP, STDDEV_SAMP}

    private StatisticalAggregates() {
    }

    public static NumberFilterTypeExpression<Double> create(ExpressionScope scope, PropTypeColumn<? extends Number> source, Kind kind) {
        scope.requireSource(source);
        Objects.requireNonNull(kind, "kind");
        // FILTER 会把来源包装成 CASE 并隐藏聚合分类，必须在包装前先检查原始样本。
        requireSample(scope, scope.operand(source, scope.runtime().fx()));
        return new NumberFilterTypeExpressionImpl<>(scope.context(), source, source.getTable(), null, (sample, factory) -> {
            SQLFunction input = scope.operand(sample, factory);
            requireSample(scope, input);
            // MySQL 上游 Double CAST 实际使用 DECIMAL，统计内部需明确请求 DOUBLE；PG/HANA 复用原转换。
            SQLFunction approximate = scope.dialect() == SqlDialect.MYSQL
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
