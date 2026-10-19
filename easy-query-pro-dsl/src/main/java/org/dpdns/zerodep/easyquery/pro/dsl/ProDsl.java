package org.dpdns.zerodep.easyquery.pro.dsl;

import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.exception.EasyQueryTableNotInSQLContextException;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.parser.core.base.scec.core.SQLNativeChainExpressionContext;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.SQLFunctionTranslateImpl;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.core.EntitySQLContext;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyColumnSegmentUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;

import java.util.Objects;

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
            SQLFunction operand = source instanceof DSLSQLFunctionAvailable function ? function.func().apply(factory)
                    // 普通属性先走上游的 SQL 属性转换；CAST 结果则使用目标类型，不再套用整列值转换器。
                    : factory.anySQLFunction("{0}", args -> args.sql(EasyColumnSegmentUtil.createColumn2Segment(source.getTable(),
                    source.getTable().getEntityMetadata().getColumnNotNull(source.getValue()), context.getExpressionContext(), false, true)));
            var cast = factory.anySQLFunction(template, args -> args.sqlFunc(source.getTable(), operand));
            return new ScopedFunction(cast, operand.getAggregationType(), source.getTable(), source instanceof DSLSQLFunctionAvailable);
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
                new ScopedFunction(factory.anySQLFunction(template, args -> args.value(boundValue)), AggregationType.UNKNOWN, anchor, false), resultType);
    }

    public <N extends Number> NumberTypeExpression<N> nullValue(NumericType<N> type) {
        return value(null, type);
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
        private final TableAvailable source;
        private final boolean derived;

        private ScopedFunction(SQLFunction delegate, AggregationType aggregation, TableAvailable source, boolean derived) {
            this.delegate = delegate;
            this.aggregation = aggregation;
            this.source = source;
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
                    if (source != null) sql.getAlias(source);
                } catch (EasyQueryTableNotInSQLContextException absent) {
                    throw new IllegalArgumentException("Pro expression cannot be reused outside its query scope", absent);
                }
                // 分片后再次合并聚合值不能等价于数据库 CAST；不静默复用 SUM/AVG 的合并算法。
                if (derived && expression.isSharding())
                    throw new UnsupportedOperationException("Precise CAST of derived expressions requires server-side evaluation before shard merging");
                return segment.toSQL(sql);
            });
        }
    }
}
