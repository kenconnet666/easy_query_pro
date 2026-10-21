package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.segment.SQLSegment;
import com.easy.query.core.expression.segment.builder.SQLBuilderSegment;
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
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;

/**
 * 将 Pro 表达式绑定到创建它的 client、查询上下文和锚点表，并传播计算语义。
 *
 * <p>SQL 仍通过上游 SQLFunction/SQLSegment 构造和执行。本类补充查询归属以及行数、分片等约束，
 * 让方言 facade 和应用扩展共享同一套规则。
 *
 * <p>“保持输出行数”与“可以逐行独立计算”是两个不同条件：普通标量通常同时满足，聚合会收缩行数；
 * 窗口虽然保持行数，仍需要数据库得到完整分区，不能据此使用普通分片合并或把外层过滤任意下推。
 */
public class ExpressionScope {
    private final EntitySQLContext context;
    private final TableAvailable anchor;
    private final QueryRuntimeContext runtime;
    private final SqlDialect dialect;

    public ExpressionScope(ProxyEntity<?, ?> entity) {
        this(Objects.requireNonNull(entity, "entity").getEntitySQLContext(), entity.getTable());
    }

    /**
     * 供分组等没有独立实体表的查询代理挂载表达式。
     * context 与 anchor 必须来自同一查询；anchor 应选实际 FROM 表，不能把分组键伪装成新实体表。
     * 最终 SQL 构造仍会核查表归属，相关子查询则在外层上下文完整后核查。
     */
    public ExpressionScope(EntitySQLContext context, TableAvailable anchor) {
        this.context = Objects.requireNonNull(context, "Use a query-bound context inside an EasyQuery query scope");
        this.anchor = Objects.requireNonNull(anchor, "A query-bound anchor is required");
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

    /**
     * 扩展作者对函数计算方式的显式声明。只有能证明逐行独立的 UNKNOWN 表达式才能关闭服务器求值标记；
     * 单看 UNKNOWN 枚举不能证明安全，因为上游复合函数可能隐藏聚合。
     */
    public SQLFunction function(SQLFunction delegate, AggregationType aggregation, boolean requiresServerEvaluation, TableAvailable... sources) {
        return new ScopedFunction(delegate, aggregation, requiresServerEvaluation,
                aggregation == AggregationType.UNKNOWN && !requiresServerEvaluation, FunctionKind.EXPRESSION, sources);
    }

    /**
     * 声明一个可信的、非 DISTINCT 裸聚合，供统计等扩展挂接 OVER。
     * 调用者保证当前方言的函数语法正确；此标记不提供分片合并算法。
     * CAST/NULLIF 等外包装应在 OVER 之后追加，不能把整个包装误当作一个裸聚合函数。
     */
    public SQLFunction aggregate(SQLFunction delegate, TableAvailable... sources) {
        return new ScopedFunction(delegate, delegate.getAggregationType(), true, false, FunctionKind.AGGREGATE, sources);
    }

    public static boolean isWindowAggregate(SQLFunction function) {
        switch (function.getAggregationType()) {
            case SUM_DISTINCT: case AVG_DISTINCT: case COUNT_DISTINCT: return false;
            default: break;
        }
        if (function instanceof ScopedFunction) return ((ScopedFunction) function).kind == FunctionKind.AGGREGATE;
        if (!(function instanceof SumSQLFunction || function instanceof AvgSQLFunction || function instanceof CountSQLFunction
                || function instanceof MinSQLFunction || function instanceof MaxSQLFunction)) return false;
        switch (function.getAggregationType()) {
            case SUM: case AVG: case COUNT: case MIN: case MAX: return true;
            default: return false;
        }
    }

    /**
     * 窗口保留输入行数，但依赖数据库完整分区。投影分类可以使用行数标记，分片检查仍必须保留。
     */
    public SQLFunction window(SQLFunction delegate, TableAvailable... sources) {
        return new ScopedFunction(delegate, AggregationType.UNKNOWN, true, true, FunctionKind.WINDOW, sources);
    }

    /**
     * 标记实际 GROUP BY 中会引入小计 NULL 的 ROLLUP/GROUPING SETS 节点。
     * 标记随 SQL 节点克隆，不写入多层查询共享的 context；外层普通投影不会被误认为仍在扩展分组。
     * 调用者必须把此函数放入真实分组段，不能用空标记干预上游优化器。
     */
    public SQLFunction expandingGroup(SQLFunction delegate, TableAvailable... sources) {
        return new ScopedFunction(delegate, AggregationType.UNKNOWN, true, false, FunctionKind.EXPANDING_GROUP, sources);
    }

    /**
     * 整值映射只透传已知语义，不把换了 Java 表达式外壳的未知函数重新声明为逐行安全。
     */
    public SQLFunction projection(SQLFunction delegate, TableAvailable... sources) {
        return new ScopedFunction(delegate, AggregationType.UNKNOWN, !isRowLocal(delegate), preservesRows(delegate), FunctionKind.EXPRESSION, sources);
    }

    /**
     * 标量包装只有在所有操作数都具备逐行保证时，才能继续作为逐行表达式。
     * 上游算术可能把 SUM/AVG 隐藏在 UNKNOWN 函数中，因此必须检查来源保证，不能只检查结果枚举。
     */
    public SQLFunction operation(SQLFunction delegate, AggregationType aggregation, SQLFunction... operands) {
        boolean requiresServerEvaluation = aggregation != AggregationType.UNKNOWN;
        boolean preservesRows = aggregation == AggregationType.UNKNOWN;
        for (SQLFunction operand : operands) {
            // UNKNOWN 还可能表示未分类的聚合包装，不能把它当成普通列表达式。
            if (!isRowLocal(operand))
                requiresServerEvaluation = true;
            preservesRows &= preservesRows(operand);
        }
        return new ScopedFunction(delegate, aggregation, requiresServerEvaluation, preservesRows, FunctionKind.EXPRESSION);
    }

    public static boolean isRowLocal(SQLFunction function) {
        return function instanceof ScopedFunction && !((ScopedFunction) function).requiresServerEvaluation && ((ScopedFunction) function).aggregation == AggregationType.UNKNOWN;
    }

    public static boolean preservesRows(SQLFunction function) {
        return function instanceof ScopedFunction && ((ScopedFunction) function).preservesRows;
    }

    /**
     * 检查同一查询层内带有 Pro 标记的窗口。别名透明，独立标量子查询在其边界停止遍历。
     * 返回 false 不证明任意上游/原生 SQL 没有窗口；未提供语义标记的入口需要显式查询边界。
     */
    public static boolean containsWindow(SQLSegment segment) {
        return containsKind(segment, FunctionKind.WINDOW);
    }

    /**
     * 当前查询层的分组节点是否可能产生小计 NULL；不进入独立子查询。
     */
    public static boolean containsNullableGrouping(SQLSegment segment) {
        return containsKind(segment, FunctionKind.EXPANDING_GROUP);
    }

    private static boolean containsKind(SQLSegment segment, FunctionKind kind) {
        ExpressionScope.SemanticVisitor visitor = new SemanticVisitor(kind);
        // 上游单节点 visitor 不展开 GROUP/SELECT 的容器，必须显式遍历其中的实际 SQL 节点。
        if (segment instanceof SQLBuilderSegment) EasySQLSegmentUtil.tableVisit(((SQLBuilderSegment) segment), visitor);
        else EasySQLSegmentUtil.sqlSegmentTableVisit(segment, visitor);
        return visitor.found;
    }

    public boolean hasWindow(SQLFunction function) {
        return containsWindow(new SQLFunctionTranslateImpl(function).toSQLSegment(context.getExpressionContext(), anchor, runtime, null));
    }

    public SQLFunction scalar(SQLFunc factory, PropTypeColumn<?> source, UnaryOperator<SQLFunction> expression) {
        SQLFunction value = operand(source, factory);
        return operation(expression.apply(value), AggregationType.UNKNOWN, value);
    }

    public SQLFunction scalar(SQLFunc factory, PropTypeColumn<?> left, PropTypeColumn<?> right,
                              BiFunction<SQLFunction, SQLFunction, SQLFunction> expression) {
        SQLFunction leftValue = operand(left, factory);
        SQLFunction rightValue = operand(right, factory);
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

    /**
     * 把来源接回上游函数链。普通属性保留原 SQL 列转换；已是函数的来源直接组合其函数，
     * 避免把派生结果再次按原实体列转换或序列化。
     */
    public SQLFunction operand(PropTypeColumn<?> source, SQLFunc factory) {
        if (source instanceof DSLSQLFunctionAvailable) return ((DSLSQLFunctionAvailable) source).func().apply(factory);
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
            ExpressionContext expression = target.getExpressionContext();
            SQLSegment segment = new SQLFunctionTranslateImpl(delegate).toSQLSegment(expression, target.getDefaultTable(), runtime, null);
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
                // 包装不保存别名分配或参数游标；最终别名仍由本次 ToSQLContext 解析。
                return this;
            }

            @Override
            public void accept(TableVisitor visitor) {
                if (visitor instanceof ServerEvaluationVisitor && requiresServerEvaluation)
                    throw ServerEvaluationVisitor.unsupported();
                if (visitor instanceof SemanticVisitor && ((SemanticVisitor) visitor).kind == kind) ((SemanticVisitor) visitor).found = true;
                // 透明包装必须继续传递返回类型和表访问，否则外层 LOB/窗口检查会丢失语义。
                EasySQLSegmentUtil.sqlSegmentTableVisit(segment, visitor);
            }

            @Override
            public String toSQL(ToSQLContext sql) {
                // 相关子查询先构造、后附着外层；必须在最终表上下文就绪后校验，不能在 consume 时误拒绝。
                try {
                    sql.getAlias(anchor);
                    for (TableAvailable source : sources) if (source != null) sql.getAlias(source);
                } catch (EasyQueryTableNotInSQLContextException absent) {
                    throw new IllegalArgumentException("Pro expression cannot be reused outside its query scope", absent);
                }
                // 分片后再次合并聚合值不能等价于数据库 CAST；不静默复用 SUM/AVG 的合并算法。
                if (requiresServerEvaluation && expression.isSharding())
                    throw ServerEvaluationVisitor.unsupported();
                return segment.toSQL(sql);
            }
        }
    }

    private enum FunctionKind {EXPRESSION, AGGREGATE, WINDOW, EXPANDING_GROUP}

    static class SemanticVisitor implements TableVisitor {
        private final FunctionKind kind;
        private boolean found;

        private SemanticVisitor(FunctionKind kind) {
            this.kind = kind;
        }

        @Override
        public Set<TableAvailable> getTables() {
            return ImmutableCollections.setOf();
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
            if (other instanceof SemanticVisitor && ((SemanticVisitor) other).kind == kind) found |= ((SemanticVisitor) other).found;
        }
    }
}
