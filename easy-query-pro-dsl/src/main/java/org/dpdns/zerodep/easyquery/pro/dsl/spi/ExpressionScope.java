package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.segment.SQLSegment;
import com.easy.query.core.expression.segment.core.TableSQLSegment;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import com.easy.query.core.expression.visitor.TableVisitor;
import com.easy.query.core.util.EasySQLSegmentUtil;
import com.easy.query.core.exception.EasyQueryTableNotInSQLContextException;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.parser.core.base.scec.core.SQLNativeChainExpressionContext;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.SQLFunctionTranslateImpl;
import com.easy.query.core.func.def.impl.SumSQLFunction;
import com.easy.query.core.func.def.impl.AvgSQLFunction;
import com.easy.query.core.func.def.impl.CountSQLFunction;
import com.easy.query.core.func.def.impl.MinSQLFunction;
import com.easy.query.core.func.def.impl.MaxSQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.core.EntitySQLContext;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyColumnSegmentUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.function.BiFunction;

/**
 * Shared client ownership, property conversion and final table-scope checks for extension facades.
 */
public class ExpressionScope {
    private final EntitySQLContext context;
    private final TableAvailable anchor;
    private final QueryRuntimeContext runtime;
    private final SqlDialect dialect;

    public ExpressionScope(ProxyEntity<?, ?> entity) {
        Objects.requireNonNull(entity, "entity");
        context = Objects.requireNonNull(entity.getEntitySQLContext(), "Use a query-bound proxy inside an EasyQuery query scope");
        anchor = Objects.requireNonNull(entity.getTable(), "A query-bound proxy is required");
        runtime = context.getRuntimeContext();
        try {
            dialect = runtime.getService(SqlDialect.class);
        } catch (IllegalArgumentException absent) {
            throw new IllegalStateException("Install the matching MySqlExtensions, PgSqlExtensions or HanaExtensions before using ProDsl", absent);
        }
    }

    public EntitySQLContext context() {
        return context;
    }

    public TableAvailable anchor() {
        return anchor;
    }

    public QueryRuntimeContext runtime() {
        return runtime;
    }

    public SqlDialect dialect() {
        return dialect;
    }

    public SQLFunction function(SQLFunction delegate, AggregationType aggregation, boolean requiresServerEvaluation, TableAvailable... sources) {
        return new ScopedFunction(delegate, aggregation, requiresServerEvaluation,
                aggregation == AggregationType.UNKNOWN && !requiresServerEvaluation, FunctionKind.EXPRESSION, sources);
    }

    /**
     * Declares a trusted, bare, non-distinct aggregate supplied by an extension.
     * The caller owns dialect syntax and must apply scalar wrappers after OVER; no shard merge is inferred.
     */
    public SQLFunction aggregate(SQLFunction delegate, TableAvailable... sources) {
        return new ScopedFunction(delegate, delegate.getAggregationType(), true, false, FunctionKind.AGGREGATE, sources);
    }

    public static boolean isWindowAggregate(SQLFunction function) {
        switch (function.getAggregationType()) {
            case SUM_DISTINCT, AVG_DISTINCT, COUNT_DISTINCT -> {
                return false;
            }
            default -> {
            }
        }
        if (function instanceof ScopedFunction scoped) return scoped.kind == FunctionKind.AGGREGATE;
        if (!(function instanceof SumSQLFunction || function instanceof AvgSQLFunction || function instanceof CountSQLFunction
                || function instanceof MinSQLFunction || function instanceof MaxSQLFunction)) return false;
        return switch (function.getAggregationType()) {
            case SUM, AVG, COUNT, MIN, MAX -> true;
            default -> false;
        };
    }

    /**
     * Windows retain rows but require the complete database partition before any shard merging.
     */
    public SQLFunction window(SQLFunction delegate, TableAvailable... sources) {
        return new ScopedFunction(delegate, AggregationType.UNKNOWN, true, true, FunctionKind.WINDOW, sources);
    }

    public SQLFunction projection(SQLFunction delegate, TableAvailable... sources) {
        return new ScopedFunction(delegate, AggregationType.UNKNOWN, !isRowLocal(delegate), preservesRows(delegate), FunctionKind.EXPRESSION, sources);
    }

    /**
     * A scalar operation stays row-local only when every operand carries that guarantee.
     */
    public SQLFunction operation(SQLFunction delegate, AggregationType aggregation, SQLFunction... operands) {
        boolean requiresServerEvaluation = aggregation != AggregationType.UNKNOWN;
        boolean preservesRows = aggregation == AggregationType.UNKNOWN;
        for (var operand : operands) {
            // UNKNOWN alone is insufficient: upstream arithmetic can hide SUM/AVG inside another function.
            if (!isRowLocal(operand))
                requiresServerEvaluation = true;
            preservesRows &= preservesRows(operand);
        }
        return new ScopedFunction(delegate, aggregation, requiresServerEvaluation, preservesRows, FunctionKind.EXPRESSION);
    }

    public static boolean isRowLocal(SQLFunction function) {
        return function instanceof ScopedFunction scoped && !scoped.requiresServerEvaluation && scoped.aggregation == AggregationType.UNKNOWN;
    }

    public static boolean preservesRows(SQLFunction function) {
        return function instanceof ScopedFunction scoped && scoped.preservesRows;
    }

    public static boolean containsWindow(SQLSegment segment) {
        var visitor = new WindowVisitor();
        EasySQLSegmentUtil.sqlSegmentTableVisit(segment, visitor);
        return visitor.found;
    }

    public boolean hasWindow(SQLFunction function) {
        return containsWindow(new SQLFunctionTranslateImpl(function).toSQLSegment(context.getExpressionContext(), anchor, runtime, null));
    }

    public SQLFunction scalar(SQLFunc factory, PropTypeColumn<?> source, UnaryOperator<SQLFunction> expression) {
        var value = operand(source, factory);
        return operation(expression.apply(value), AggregationType.UNKNOWN, value);
    }

    public SQLFunction scalar(SQLFunc factory, PropTypeColumn<?> left, PropTypeColumn<?> right,
                              BiFunction<SQLFunction, SQLFunction, SQLFunction> expression) {
        var leftValue = operand(left, factory);
        var rightValue = operand(right, factory);
        return operation(expression.apply(leftValue, rightValue), AggregationType.UNKNOWN, leftValue, rightValue);
    }

    public <T> PropTypeColumn<T> comparisonParameter(PropTypeColumn<T> left, T value) {
        requireSource(left);
        return new AnyTypeExpressionImpl<>(context, anchor, null, factory -> {
            if (left instanceof DSLSQLFunctionAvailable) {
                return function(factory.anySQLFunction("{0}", args -> args.value(left._toFunctionSerializeValue(value))), AggregationType.UNKNOWN, false);
            }
            // 保留列元数据，让上游比较参数链完成值转换、加密与列 handler 选择，避免提前序列化两次。
            return function(factory.anySQLFunction("{0}", args -> args.sql(EasyColumnSegmentUtil.createColumnCompareValue2Segment(left.getTable(),
                    left.getTable().getEntityMetadata().getColumnNotNull(left.getValue()), context.getExpressionContext(), value))), AggregationType.UNKNOWN, false);
        }, left.getPropertyType());
    }

    public SQLFunction operand(PropTypeColumn<?> source, SQLFunc factory) {
        if (source instanceof DSLSQLFunctionAvailable function) return function.func().apply(factory);
        // 普通属性先走上游的 SQL 属性转换；派生结果不再套用整列值转换器。
        return function(factory.anySQLFunction("{0}", args -> args.sql(EasyColumnSegmentUtil.createColumn2Segment(source.getTable(),
                        source.getTable().getEntityMetadata().getColumnNotNull(source.getValue()), context.getExpressionContext(), false, true))),
                AggregationType.UNKNOWN, false, source.getTable());
    }

    public void requireSource(PropTypeColumn<?> source) {
        Objects.requireNonNull(source, "source");
        if (source.getEntitySQLContext().getRuntimeContext() != runtime)
            throw new IllegalArgumentException("Pro expression cannot use a column from another client");
    }

    private final class ScopedFunction implements SQLFunction {
        private final SQLFunction delegate;
        private final AggregationType aggregation;
        private final TableAvailable[] sources;
        private final boolean requiresServerEvaluation;
        private final boolean preservesRows;
        private final FunctionKind kind;

        private ScopedFunction(SQLFunction delegate, AggregationType aggregation, boolean requiresServerEvaluation,
                               boolean preservesRows, FunctionKind kind, TableAvailable... sources) {
            this.delegate = delegate;
            this.aggregation = aggregation;
            this.sources = sources;
            this.requiresServerEvaluation = requiresServerEvaluation;
            this.preservesRows = preservesRows;
            this.kind = kind;
        }

        @Override
        public String sqlSegment(TableAvailable table) {
            return "{0}";
        }

        @Override
        public int paramMarks() {
            return 1;
        }

        @Override
        public AggregationType getAggregationType() {
            return aggregation;
        }

        @Override
        public void consume(SQLNativeChainExpressionContext target) {
            if (target.getRuntimeContext() != runtime)
                throw new IllegalArgumentException("Pro expression cannot be reused by another client");
            var expression = target.getExpressionContext();
            var segment = new SQLFunctionTranslateImpl(delegate).toSQLSegment(expression, target.getDefaultTable(), runtime, null);
            target.sql(new ScopedSegment(expression, segment));
        }

        private final class ScopedSegment implements TableSQLSegment {
            private final ExpressionContext expression;
            private final SQLSegment segment;

            private ScopedSegment(ExpressionContext expression, SQLSegment segment) {
                this.expression = expression;
                this.segment = segment;
            }

            @Override
            public TableAvailable getTable() {
                return anchor;
            }

            @Override
            public ScopedSegment cloneSQLColumnSegment() {
                return this;
            }

            @Override
            public void accept(TableVisitor visitor) {
                if (kind == FunctionKind.WINDOW && visitor instanceof WindowVisitor windows) windows.found = true;
                // Preserve the wrapped result and table metadata through this transparent scope boundary.
                EasySQLSegmentUtil.sqlSegmentTableVisit(segment, visitor);
            }

            @Override
            public String toSQL(ToSQLContext sql) {
                // 相关子查询先构造、后附着外层；必须在最终表上下文就绪后校验，不能在 consume 时误拒绝。
                try {
                    sql.getAlias(anchor);
                    for (var source : sources) if (source != null) sql.getAlias(source);
                } catch (EasyQueryTableNotInSQLContextException absent) {
                    throw new IllegalArgumentException("Pro expression cannot be reused outside its query scope", absent);
                }
                // 分片后再次合并聚合值不能等价于数据库 CAST；不静默复用 SUM/AVG 的合并算法。
                if (requiresServerEvaluation && expression.isSharding())
                    throw new UnsupportedOperationException("Pro window, aggregate or unclassified operations require server-side evaluation before shard merging");
                return segment.toSQL(sql);
            }
        }
    }

    private enum FunctionKind {EXPRESSION, AGGREGATE, WINDOW}

    static class WindowVisitor implements TableVisitor {
        private boolean found;

        @Override
        public Set<TableAvailable> getTables() {
            return Set.of();
        }

        @Override
        public void visit(TableAvailable table) {
        }

        @Override
        public boolean containsTable(TableAvailable table) {
            return false;
        }

        @Override
        public void addAll(TableVisitor other) {
            if (other instanceof WindowVisitor windows) found |= windows.found;
        }
    }
}
