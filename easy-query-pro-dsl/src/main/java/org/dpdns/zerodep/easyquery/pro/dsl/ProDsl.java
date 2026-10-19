package org.dpdns.zerodep.easyquery.pro.dsl;

import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.exception.EasyQueryTableNotInSQLContextException;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.parser.core.base.scec.core.SQLNativeChainExpressionContext;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunctionTranslateImpl;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.core.EntitySQLContext;
import com.easy.query.core.proxy.extension.functions.ColumnNumberFunctionAvailable;
import com.easy.query.core.proxy.extension.functions.ColumnStringFunctionAvailable;
import com.easy.query.core.proxy.extension.functions.ColumnDateTimeFunctionAvailable;
import com.easy.query.core.proxy.extension.functions.ColumnBooleanFunctionAvailable;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.DateTimeTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.IgnoreValueConverterTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.DateTimeTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyColumnSegmentUtil;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.NullSafeComparison;

import java.util.Objects;
import java.util.function.Function;

/**
 * Portable additions that compose with EasyQuery's existing query and expression types.
 */
public final class ProDsl {
    private final EntitySQLContext context;
    private final TableAvailable anchor;
    private final QueryRuntimeContext runtime;
    private final SqlDialect dialect;

    private ProDsl(ProxyEntity<?, ?> entity) {
        Objects.requireNonNull(entity, "entity");
        context = Objects.requireNonNull(entity.getEntitySQLContext(), "Use ProDsl.of inside an EasyQuery query scope");
        anchor = Objects.requireNonNull(entity.getTable(), "A query-bound proxy is required");
        runtime = context.getRuntimeContext();
        try {
            dialect = runtime.getService(SqlDialect.class);
        } catch (IllegalArgumentException absent) {
            throw new IllegalStateException("Install the matching MySqlExtensions, PgSqlExtensions or HanaExtensions before using ProDsl", absent);
        }
    }

    public static ProDsl of(ProxyEntity<?, ?> entity) {
        return new ProDsl(entity);
    }

    /**
     * Emits the database's CAST, including its native rounding, overflow and invalid-input behavior.
     */
    public <N extends Number> NumberTypeExpression<N> nativeCast(PropTypeColumn<?> source, NumericType<N> target) {
        requireSource(source);
        Objects.requireNonNull(target, "target");
        String template = target.castTemplate(dialect);
        Class<N> resultType = boxedTarget(target.javaType());
        return new NumberTypeExpressionImpl<>(context, anchor, null, factory -> {
            SQLFunction operand = operand(source, factory);
            var cast = factory.anySQLFunction(template, args -> args.sqlFunc(source.getTable(), operand));
            return new ScopedFunction(cast, operand.getAggregationType(), source instanceof DSLSQLFunctionAvailable, source.getTable());
        }, resultType);
    }

    /**
     * Binds an exactly representable value; precision loss is rejected before SQL execution.
     */
    public <N extends Number> NumberTypeExpression<N> value(N value, NumericType<N> type) {
        Objects.requireNonNull(type, "type");
        Class<N> resultType = boxedTarget(type.javaType());
        if (value != null && !resultType.isInstance(value))
            throw new IllegalArgumentException("Expected " + resultType.getName());
        N boundValue = type.prepareValue(value);
        String template = type.castTemplate(dialect);
        return new NumberTypeExpressionImpl<>(context, anchor, null, factory ->
                new ScopedFunction(factory.anySQLFunction(template, args -> args.value(boundValue)), AggregationType.UNKNOWN, false), resultType);
    }

    public <N extends Number> NumberTypeExpression<N> nullValue(NumericType<N> type) {
        return value(null, type);
    }

    /**
     * Returns true for two NULLs, false for exactly one NULL, and SQL equality otherwise.
     */
    public <T> BooleanTypeExpression<Boolean> nullSafeEquals(PropTypeColumn<T> left, PropTypeColumn<T> right) {
        return comparison(left, right, false);
    }

    public <T> BooleanTypeExpression<Boolean> nullSafeEqualsValue(PropTypeColumn<T> left, T value) {
        return comparisonValue(left, value, false);
    }

    /**
     * The two-valued inverse of nullSafeEquals.
     */
    public <T> BooleanTypeExpression<Boolean> isDistinctFrom(PropTypeColumn<T> left, PropTypeColumn<T> right) {
        return comparison(left, right, true);
    }

    public <T> BooleanTypeExpression<Boolean> isDistinctFromValue(PropTypeColumn<T> left, T value) {
        return comparisonValue(left, value, true);
    }

    public <N extends Number> NumberTypeExpression<N> nullIf(ColumnNumberFunctionAvailable<N> left, PropTypeColumn<N> right) {
        return new NumberTypeExpressionImpl<>(context, left.getTable(), resultProperty(left), nullIfFunction(left, right),
                resultType(left));
    }

    public StringTypeExpression<String> nullIf(ColumnStringFunctionAvailable<String> left, PropTypeColumn<String> right) {
        return new StringTypeExpressionImpl<>(context, left.getTable(), resultProperty(left), nullIfFunction(left, right), String.class);
    }

    public <T> DateTimeTypeExpression<T> nullIf(ColumnDateTimeFunctionAvailable<T> left, PropTypeColumn<T> right) {
        return new DateTimeTypeExpressionImpl<>(context, left.getTable(), resultProperty(left), nullIfFunction(left, right), resultType(left));
    }

    public BooleanTypeExpression<Boolean> nullIf(ColumnBooleanFunctionAvailable<Boolean> left, PropTypeColumn<Boolean> right) {
        return new BooleanTypeExpressionImpl<>(context, left.getTable(), resultProperty(left), nullIfFunction(left, right), Boolean.class);
    }

    public <N extends Number> NumberTypeExpression<N> nullIfValue(ColumnNumberFunctionAvailable<N> left, N value) {
        return nullIf(left, comparisonParameter(left, value));
    }

    public StringTypeExpression<String> nullIfValue(ColumnStringFunctionAvailable<String> left, String value) {
        return nullIf(left, comparisonParameter(left, value));
    }

    public <T> DateTimeTypeExpression<T> nullIfValue(ColumnDateTimeFunctionAvailable<T> left, T value) {
        return nullIf(left, comparisonParameter(left, value));
    }

    public BooleanTypeExpression<Boolean> nullIfValue(ColumnBooleanFunctionAvailable<Boolean> left, Boolean value) {
        return nullIf(left, comparisonParameter(left, value));
    }

    private Function<SQLFunc, SQLFunction> nullIfFunction(PropTypeColumn<?> left, PropTypeColumn<?> right) {
        requireSource(left);
        requireSource(right);
        return factory -> new ScopedFunction(factory.anySQLFunction("NULLIF({0},{1})", args -> {
            args.sqlFunc(left.getTable(), operand(left, factory));
            args.sqlFunc(right.getTable(), operand(right, factory));
        }), AggregationType.UNKNOWN, true, left.getTable(), right.getTable());
    }

    private static String resultProperty(PropTypeColumn<?> source) {
        // asStr 等显式视图不再使用原整列转换器；同类型 NULLIF 则保留普通列的结果映射。
        return source instanceof IgnoreValueConverterTypeExpression ? null : source.getValue();
    }

    private static Class<?> resultType(PropTypeColumn<?> source) {
        return EasyClassUtil.getObjectTypeWhenPrimitive(Objects.requireNonNull(source.getPropertyType(), "The source Java type is required"));
    }

    private <T> BooleanTypeExpression<Boolean> comparisonValue(PropTypeColumn<T> left, T value, boolean distinct) {
        return comparison(left, comparisonParameter(left, value), distinct);
    }

    private <T> PropTypeColumn<T> comparisonParameter(PropTypeColumn<T> left, T value) {
        requireSource(left);
        return new AnyTypeExpressionImpl<>(context, anchor, null, factory -> {
            if (left instanceof DSLSQLFunctionAvailable) {
                return factory.anySQLFunction("{0}", args -> args.value(left._toFunctionSerializeValue(value)));
            }
            // 保留列元数据，让上游比较参数链完成值转换、加密与列 handler 选择，避免提前序列化两次。
            return factory.anySQLFunction("{0}", args -> args.sql(EasyColumnSegmentUtil.createColumnCompareValue2Segment(left.getTable(),
                    left.getTable().getEntityMetadata().getColumnNotNull(left.getValue()), context.getExpressionContext(), value)));
        }, left.getPropertyType());
    }

    private BooleanTypeExpression<Boolean> comparison(PropTypeColumn<?> left, PropTypeColumn<?> right, boolean distinct) {
        requireSource(left);
        requireSource(right);
        var templates = runtime.getService(NullSafeComparison.class);
        String template = distinct ? templates.distinct() : templates.equal();
        return new BooleanTypeExpressionImpl<>(context, anchor, null, factory -> {
            var result = factory.anySQLFunction(template, args -> {
                args.sqlFunc(left.getTable(), operand(left, factory));
                args.sqlFunc(right.getTable(), operand(right, factory));
            });
            return new ScopedFunction(result, AggregationType.UNKNOWN,
                    left instanceof DSLSQLFunctionAvailable || right instanceof DSLSQLFunctionAvailable, left.getTable(), right.getTable());
        }, Boolean.class);
    }

    private SQLFunction operand(PropTypeColumn<?> source, SQLFunc factory) {
        if (source instanceof DSLSQLFunctionAvailable function) return function.func().apply(factory);
        // 普通属性先走上游的 SQL 属性转换；派生结果不再套用整列值转换器。
        return factory.anySQLFunction("{0}", args -> args.sql(EasyColumnSegmentUtil.createColumn2Segment(source.getTable(),
                source.getTable().getEntityMetadata().getColumnNotNull(source.getValue()), context.getExpressionContext(), false, true)));
    }

    private static <N extends Number> Class<N> boxedTarget(Class<N> type) {
        Objects.requireNonNull(type, "target Java type");
        if (type.isPrimitive())
            throw new IllegalArgumentException("Pro expressions require boxed result types so SQL NULL is preserved");
        return type;
    }

    private void requireSource(PropTypeColumn<?> source) {
        Objects.requireNonNull(source, "source");
        if (source.getEntitySQLContext().getRuntimeContext() != runtime)
            throw new IllegalArgumentException("Pro expression cannot use a column from another client");
    }

    private final class ScopedFunction implements SQLFunction {
        private final SQLFunction delegate;
        private final AggregationType aggregation;
        private final TableAvailable[] sources;
        private final boolean derived;

        private ScopedFunction(SQLFunction delegate, AggregationType aggregation, boolean derived, TableAvailable... sources) {
            this.delegate = delegate;
            this.aggregation = aggregation;
            this.sources = sources;
            this.derived = derived;
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
            target.sql(sql -> {
                // 相关子查询先构造、后附着外层；必须在最终表上下文就绪后校验，不能在 consume 时误拒绝。
                try {
                    sql.getAlias(anchor);
                    for (var source : sources) if (source != null) sql.getAlias(source);
                } catch (EasyQueryTableNotInSQLContextException absent) {
                    throw new IllegalArgumentException("Pro expression cannot be reused outside its query scope", absent);
                }
                // 分片后再次合并聚合值不能等价于数据库 CAST；不静默复用 SUM/AVG 的合并算法。
                if (derived && expression.isSharding())
                    throw new UnsupportedOperationException("Pro operations on derived expressions require server-side evaluation before shard merging");
                return segment.toSQL(sql);
            });
        }
    }
}
