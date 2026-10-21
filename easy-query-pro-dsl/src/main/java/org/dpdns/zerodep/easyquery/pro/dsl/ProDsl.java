package org.dpdns.zerodep.easyquery.pro.dsl;

import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.SQLFunc;
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
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import org.dpdns.zerodep.easyquery.pro.dsl.type.TextType;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CastTarget;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.NullSafeComparison;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.StatisticalAggregates;
import com.easy.query.core.proxy.extension.functions.type.filter.NumberFilterTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RowsWindowSpec;
import org.dpdns.zerodep.easyquery.pro.dsl.window.WindowSpec;
import org.dpdns.zerodep.easyquery.pro.dsl.window.WindowFunctions;
import com.easy.query.core.proxy.extension.functions.ColumnObjectFunctionAvailable;
import com.easy.query.core.proxy.extension.functions.ColumnAnyFunctionAvailable;
import com.easy.query.core.proxy.extension.functions.type.AnyTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.MappedValueExpression;
import com.easy.query.api.proxy.extension.window.NextSelector;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.DateTimeTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonFragmentExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ScalarComparisonRestriction;

import java.util.Objects;
import java.util.function.Function;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 可与 EasyQuery 原查询和表达式组合的共同增强入口。
 *
 * <p>优先提供三库语义明确的能力，真实方言差异由各数据库 facade 增补。facade 共用 ExpressionScope，
 * 应用可继承并通过工厂挂载具体类型，无需重建查询执行器。SQL CAST、Java 操作视图和参数值校验
 * 承担不同职责，不能因 Java 返回类型相同就互相替代。
 */
public class ProDsl {
    protected final ExpressionScope scope;
    private final EntitySQLContext context;
    private final TableAvailable anchor;
    private final QueryRuntimeContext runtime;
    private final SqlDialect dialect;

    protected ProDsl(ProxyEntity<?, ?> entity) {
        this(new ExpressionScope(entity));
    }

    protected ProDsl(ExpressionScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        context = scope.context();
        anchor = scope.anchor();
        runtime = scope.runtime();
        dialect = scope.dialect();
    }

    public static ProDsl of(ProxyEntity<?, ?> entity) {
        return new ProDsl(entity);
    }

    /**
     * 在当前查询中挂载具体 DSL，返回类型保留 D，应用自定义方法不会退化成基础 ProDsl。
     * 工厂应调用对应 facade 的受保护构造器，保留数据库身份与查询归属检查。
     */
    public static <D extends ProDsl> D of(ProxyEntity<?, ?> entity, Function<? super ExpressionScope, ? extends D> factory) {
        return Objects.requireNonNull(Objects.requireNonNull(factory, "factory").apply(new ExpressionScope(entity)), "factory result");
    }

    /**
     * 总体方差；样本先转 DOUBLE 再统计，避免不同数据库按 DECIMAL scale 提前丢失小数。
     *
     * <p>四项统计都返回可空 Double，忽略 NULL；POP 单个有效样本为零，SAMP 少于两个有效样本为 NULL。
     * BigDecimal 输入也先转近似值，不提供精确金额统计或跨分片合并。条件样本使用上游 filter。
     * 无 GROUP BY 的单行聚合报告如需 count/page，应先 toCteAs 固定聚合结果层。
     */
    public NumberFilterTypeExpression<Double> variancePopulation(PropTypeColumn<? extends Number> source) {
        return StatisticalAggregates.create(scope, source, StatisticalAggregates.Kind.VAR_POP);
    }

    /**
     * 样本方差；忽略 NULL，少于两个有效样本返回 NULL，条件样本继续使用上游 filter。
     *
     * @see #variancePopulation(PropTypeColumn) 四项统计共用的 DOUBLE 精度与查询边界约定
     */
    public NumberFilterTypeExpression<Double> varianceSample(PropTypeColumn<? extends Number> source) {
        return StatisticalAggregates.create(scope, source, StatisticalAggregates.Kind.VAR_SAMP);
    }

    /**
     * 总体标准差，采用共同近似 Double 契约；单个有效样本为零，空样本为 NULL。
     *
     * @see #variancePopulation(PropTypeColumn) 四项统计共用的 DOUBLE 精度与查询边界约定
     */
    public NumberFilterTypeExpression<Double> standardDeviationPopulation(PropTypeColumn<? extends Number> source) {
        return StatisticalAggregates.create(scope, source, StatisticalAggregates.Kind.STDDEV_POP);
    }

    /**
     * 样本标准差；明确使用 SAMP，不依赖各数据库含义可能不同的 STDDEV 别名。
     *
     * @see #variancePopulation(PropTypeColumn) 四项统计共用的 DOUBLE 精度与查询边界约定
     */
    public NumberFilterTypeExpression<Double> standardDeviationSample(PropTypeColumn<? extends Number> source) {
        return StatisticalAggregates.create(scope, source, StatisticalAggregates.Kind.STDDEV_SAMP);
    }

    /**
     * 创建共同窗口规格；空 partition 表示全局窗口，ROWS 边界与排序按业务显式指定。
     */
    public RowsWindowSpec window() {
        return new RowsWindowSpec(scope);
    }

    /**
     * 挂载应用或方言规格并保留具体 W。SELF 约束由规格自身维护，不在这里重复为 WindowSpec&lt;W&gt;，
     * 因而既接受返回通配符规格的内置工厂，也接受返回具体子类的构造器。
     */
    public <W extends WindowSpec<?>> W window(Function<? super ExpressionScope, ? extends W> factory) {
        return Objects.requireNonNull(Objects.requireNonNull(factory, "factory").apply(scope), "factory result");
    }

    /**
     * 按完整排序键判断并列，后续名次保留跳号；不指定 partition 即为全局排名。
     * 排序必须显式提供，不能附带 ROWS/RANGE 等 frame。NULL 顺序沿目标数据库规则。
     */
    public NumberTypeExpression<Long> rank(WindowSpec<?> window) {
        return WindowFunctions.rank(scope, window);
    }

    /**
     * 并列行名次相同，下一组继续连续编号。不要为了稳定输出而把唯一键加入该窗口排序，
     * 否则会改变 peer 分组；输出顺序应在查询外层另外指定。
     */
    public NumberTypeExpression<Long> denseRank(WindowSpec<?> window) {
        return WindowFunctions.denseRank(scope, window);
    }

    /**
     * 从 1 开始为每行编号；若业务需要稳定选出一行，应给窗口补充唯一的次排序键。
     * 与另两种排名共用 WindowSpec、Long 结果和窗口计算边界，不接受 frame。
     */
    public NumberTypeExpression<Long> rowNumber(WindowSpec<?> window) {
        return WindowFunctions.rowNumber(scope, window);
    }

    /**
     * 将已有裸聚合挂到窗口，保留原数值/文本等表达式族；标量包装应在 OVER 之后追加。
     */
    public <T, C extends PropTypeColumn<T>> C over(ColumnObjectFunctionAvailable<T, C> aggregate, WindowSpec<?> window) {
        return WindowFunctions.aggregate(scope, aggregate, window);
    }

    /**
     * 对象族额外保留源 reader，使派生业务值的 MIN/MAX 窗口不退回 JDBC 原始对象。
     */
    public <T> AnyTypeExpression<T> over(ColumnAnyFunctionAvailable<T> aggregate, WindowSpec<?> window) {
        return WindowFunctions.aggregate(scope, aggregate, window, (bound, mapping, function) -> new MappedValueExpression<T>(bound, mapping, function) {
        });
    }

    /**
     * 工厂重载返回具体 E，窗口之后仍可调用自定义方法；映射来源由共同入口传入。
     */
    public <T, E extends PropTypeColumn<T>> E over(PropTypeColumn<T> aggregate, WindowSpec<?> window, ExpressionFactory<T, ? extends E> factory) {
        return WindowFunctions.aggregate(scope, aggregate, window, factory);
    }

    /**
     * 复用 firstValue/lastValue/nthValue；要求明确排序，next/prev 继续走上游 offset API。
     */
    public <T, C extends PropTypeColumn<T>> NextSelector<T, C> overValue(ColumnObjectFunctionAvailable<T, C> source, WindowSpec<?> window) {
        return WindowFunctions.value(scope, source, window);
    }

    public <T> NextSelector<T, AnyTypeExpression<T>> overValue(ColumnAnyFunctionAvailable<T> source, WindowSpec<?> window) {
        return overValue((PropTypeColumn<T>) source, window);
    }

    public <T> NextSelector<T, AnyTypeExpression<T>> overValue(PropTypeColumn<T> source, WindowSpec<?> window) {
        return WindowFunctions.value(scope, source, window, (bound, mapping, function) -> new MappedValueExpression<T>(bound, mapping, function) {
        });
    }

    public <T, E extends PropTypeColumn<T>> NextSelector<T, E> overValue(PropTypeColumn<T> source, WindowSpec<?> window, ExpressionFactory<T, ? extends E> factory) {
        return WindowFunctions.value(scope, source, window, factory);
    }

    /**
     * 生成实际数据库 CAST，保留原生舍入、溢出及非法输入行为。
     * 这与参数 value 的无损预校验不同，也与不生成转换 SQL 的 view 不同。
     */
    public <N extends Number> NumberTypeExpression<N> nativeCast(PropTypeColumn<?> source, NumericType<N> target) {
        Objects.requireNonNull(target, "target");
        Class<N> resultType = boxedTarget(target.javaType());
        return new NumberTypeExpressionImpl<>(context, anchor, null, conversion(source, target), resultType);
    }

    /**
     * 文本 CAST 采用原生截断规则；需要无损校验的输入参数应使用 value。
     */
    public StringTypeExpression<String> nativeCast(PropTypeColumn<?> source, TextType target) {
        return new StringTypeExpressionImpl<>(context, anchor, null, conversion(source, target), String.class);
    }

    public <T, E extends PropTypeColumn<T>> E nativeCast(PropTypeColumn<?> source, CastTarget<T> target, ExpressionFactory<T, ? extends E> factory) {
        Objects.requireNonNull(target, "target");
        Function<SQLFunc, SQLFunction> expression = conversion(source, target);
        return create(factory, new AnyTypeExpressionImpl<>(context, anchor, null, expression, boxedTarget(target.javaType())), expression);
    }

    /**
     * 为已有 SQL 值挂载操作视图，不改变物理存储或原读取映射。
     * 工厂必须沿用传入的来源；具体类型族的物理声明检查由其构造器负责。
     */
    public <T, E extends PropTypeColumn<T>> E view(PropTypeColumn<T> source, ExpressionFactory<T, ? extends E> factory) {
        scope.requireSource(source);
        return create(factory, source, sql -> scope.operand(source, sql));
    }

    private Function<SQLFunc, SQLFunction> conversion(PropTypeColumn<?> source, CastTarget<?> target) {
        scope.requireSource(source);
        Objects.requireNonNull(target, "target");
        String template = target.castTemplate(dialect);
        return factory -> {
            SQLFunction operand = scope.operand(source, factory);
            SQLFunction cast = factory.anySQLFunction(template, args -> args.sqlFunc(source.getTable(), operand));
            return scope.operation(cast, operand.getAggregationType(), operand);
        };
    }

    /**
     * 绑定能被目标类型精确表示的参数，执行前拒绝精度损失，不静默舍入输入值。
     */
    public <N extends Number> NumberTypeExpression<N> value(N value, NumericType<N> type) {
        Objects.requireNonNull(type, "type");
        Class<N> resultType = boxedTarget(type.javaType());
        return new NumberTypeExpressionImpl<>(context, anchor, null, parameter(value, type), resultType);
    }

    public <N extends Number> NumberTypeExpression<N> nullValue(NumericType<N> type) {
        return value(null, type);
    }

    public StringTypeExpression<String> value(String value, TextType type) {
        return new StringTypeExpressionImpl<>(context, anchor, null, parameter(value, type), String.class);
    }

    public <T, E extends PropTypeColumn<T>> E value(T value, CastTarget<T> type, ExpressionFactory<T, ? extends E> factory) {
        Function<SQLFunc, SQLFunction> expression = parameter(value, type);
        return create(factory, new AnyTypeExpressionImpl<>(context, anchor, null, expression, boxedTarget(type.javaType())), expression);
    }

    protected <T, E extends PropTypeColumn<T>> E create(ExpressionFactory<T, ? extends E> factory, PropTypeColumn<T> source,
                                                        Function<SQLFunc, SQLFunction> expression) {
        return Objects.requireNonNull(Objects.requireNonNull(factory, "factory").create(scope, source, expression), "factory result");
    }

    private <T> Function<SQLFunc, SQLFunction> parameter(T value, CastTarget<T> type) {
        Objects.requireNonNull(type, "type");
        Class<T> resultType = boxedTarget(type.javaType());
        if (value != null && !resultType.isInstance(value))
            throw new IllegalArgumentException("Expected " + resultType.getName());
        T boundValue = type.prepareValue(value);
        String template = type.castTemplate(dialect);
        return factory -> scope.function(factory.anySQLFunction(template, args -> args.value(boundValue)), AggregationType.UNKNOWN, false);
    }

    public StringTypeExpression<String> nullValue(TextType type) {
        return value(null, type);
    }

    public JsonFragmentExpression jsonAt(PropTypeColumn<?> document, JsonPath path) {
        return new Fragment(document, path);
    }

    public StringTypeExpression<String> jsonTextAt(PropTypeColumn<?> document, JsonPath path) {
        return new StringTypeExpressionImpl<>(context, anchor, null, jsonRead(document, path, JsonResult.TEXT), String.class);
    }

    public BooleanTypeExpression<Boolean> jsonBooleanAt(PropTypeColumn<?> document, JsonPath path) {
        return new BooleanTypeExpressionImpl<>(context, anchor, null, jsonRead(document, path, JsonResult.BOOLEAN), Boolean.class);
    }

    public BooleanTypeExpression<Boolean> jsonExists(PropTypeColumn<?> document, JsonPath path) {
        return new BooleanTypeExpressionImpl<>(context, anchor, null, jsonRead(document, path, JsonResult.EXISTS), Boolean.class);
    }

    private Function<SQLFunc, SQLFunction> jsonRead(PropTypeColumn<?> document, JsonPath path, JsonResult result) {
        scope.requireSource(document);
        Objects.requireNonNull(path, "path");
        JsonPathAccess access = runtime.getService(JsonPathAccess.class);
        access.requireNavigationSupport();
        return factory -> {
            SQLFunction operand = scope.operand(document, factory);
            SQLFunction rooted = factory.anySQLFunction("{0}", args -> args.sqlFunc(document.getTable(), operand));
            SQLFunction node = access.fragment(factory, rooted, path);
            SQLFunction selected;
            switch (result) {
                case FRAGMENT: selected = node; break;
                case TEXT: selected = access.scalarText(factory, node); break;
                case SERIALIZED: selected = access.serializedText(factory, node); break;
                case EXISTS: selected = factory.anySQLFunction("(CASE WHEN {0} IS NOT NULL THEN TRUE ELSE FALSE END)", args -> args.sqlFunc(node)); break;
                case BOOLEAN: selected = factory.anySQLFunction("(CASE {0} WHEN 'true' THEN TRUE WHEN 'false' THEN FALSE ELSE NULL END)",
                                args -> args.sqlFunc(access.serializedText(factory, node))); break;
                default: throw new IllegalStateException("Unhandled value: " + result);
            }
            return scope.operation(selected, AggregationType.UNKNOWN, operand);
        };
    }

    private enum JsonResult {FRAGMENT, TEXT, SERIALIZED, EXISTS, BOOLEAN}

    private final class Fragment extends AnyTypeExpressionImpl<String> implements JsonFragmentExpression {
        private final PropTypeColumn<?> document;
        private final JsonPath path;

        private Fragment(PropTypeColumn<?> document, JsonPath path) {
            super(context, anchor, null, jsonRead(document, path, JsonResult.FRAGMENT), String.class);
            this.document = document;
            this.path = path;
        }

        @Override
        public StringTypeExpression<String> asText() {
            return new StringTypeExpressionImpl<>(context, anchor, null, jsonRead(document, path, JsonResult.SERIALIZED), String.class);
        }
    }

    public DateTimeTypeExpression<LocalDate> truncateDate(PropTypeColumn<LocalDate> source, TruncationUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (!unit.isDateUnit())
            throw new IllegalArgumentException("LocalDate does not support " + unit + " truncation");
        return new DateTimeTypeExpressionImpl<>(context, anchor, null, truncation(source, unit, true), LocalDate.class);
    }

    public DateTimeTypeExpression<LocalDateTime> truncateDateTime(PropTypeColumn<LocalDateTime> source, TruncationUnit unit) {
        return new DateTimeTypeExpressionImpl<>(context, anchor, null, truncation(source, unit, false), LocalDateTime.class);
    }

    private Function<SQLFunc, SQLFunction> truncation(PropTypeColumn<?> source, TruncationUnit unit, boolean dateOnly) {
        scope.requireSource(source);
        Objects.requireNonNull(unit, "unit");
        String template = runtime.getService(TemporalTruncation.class).template(unit, dateOnly);
        // 上游 SQLFormatArgument 按每次出现延迟渲染，重复 {0} 会产生对应的独立绑定。
        return factory -> {
            SQLFunction operand = scope.operand(source, factory);
            return scope.operation(factory.anySQLFunction(template, args -> args.sqlFunc(source.getTable(), operand)), AggregationType.UNKNOWN, operand);
        };
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
        return nullIf(left, scope.comparisonParameter(left, value));
    }

    public StringTypeExpression<String> nullIfValue(ColumnStringFunctionAvailable<String> left, String value) {
        return nullIf(left, scope.comparisonParameter(left, value));
    }

    public <T> DateTimeTypeExpression<T> nullIfValue(ColumnDateTimeFunctionAvailable<T> left, T value) {
        return nullIf(left, scope.comparisonParameter(left, value));
    }

    public BooleanTypeExpression<Boolean> nullIfValue(ColumnBooleanFunctionAvailable<Boolean> left, Boolean value) {
        return nullIf(left, scope.comparisonParameter(left, value));
    }

    private Function<SQLFunc, SQLFunction> nullIfFunction(PropTypeColumn<?> left, PropTypeColumn<?> right) {
        scope.requireSource(left);
        scope.requireSource(right);
        return factory -> {
            SQLFunction leftOperand = scope.operand(left, factory);
            SQLFunction rightOperand = scope.operand(right, factory);
            return scope.operation(factory.anySQLFunction("NULLIF({0},{1})", args -> {
                args.sqlFunc(left.getTable(), leftOperand);
                args.sqlFunc(right.getTable(), rightOperand);
            }), AggregationType.UNKNOWN, leftOperand, rightOperand);
        };
    }

    private static String resultProperty(PropTypeColumn<?> source) {
        // asStr 等显式视图不再使用原整列转换器；同类型 NULLIF 则保留普通列的结果映射。
        return source instanceof IgnoreValueConverterTypeExpression ? null : source.getValue();
    }

    private static Class<?> resultType(PropTypeColumn<?> source) {
        return EasyClassUtil.getObjectTypeWhenPrimitive(Objects.requireNonNull(source.getPropertyType(), "The source Java type is required"));
    }

    private <T> BooleanTypeExpression<Boolean> comparisonValue(PropTypeColumn<T> left, T value, boolean distinct) {
        return comparison(left, scope.comparisonParameter(left, value), distinct);
    }

    private BooleanTypeExpression<Boolean> comparison(PropTypeColumn<?> left, PropTypeColumn<?> right, boolean distinct) {
        scope.requireSource(left);
        scope.requireSource(right);
        if (left instanceof ScalarComparisonRestriction)
            throw new IllegalArgumentException(((ScalarComparisonRestriction) left).scalarComparisonError());
        if (right instanceof ScalarComparisonRestriction)
            throw new IllegalArgumentException(((ScalarComparisonRestriction) right).scalarComparisonError());
        NullSafeComparison templates = runtime.getService(NullSafeComparison.class);
        String template = distinct ? templates.distinct() : templates.equal();
        return new BooleanTypeExpressionImpl<>(context, anchor, null, factory -> {
            SQLFunction leftOperand = scope.operand(left, factory);
            SQLFunction rightOperand = scope.operand(right, factory);
            SQLFunction result = factory.anySQLFunction(template, args -> {
                args.sqlFunc(left.getTable(), leftOperand);
                args.sqlFunc(right.getTable(), rightOperand);
            });
            return scope.operation(result, AggregationType.UNKNOWN, leftOperand, rightOperand);
        }, Boolean.class);
    }

    private static <T> Class<T> boxedTarget(Class<T> type) {
        Objects.requireNonNull(type, "target Java type");
        if (type.isPrimitive())
            throw new IllegalArgumentException("Pro expressions require boxed result types so SQL NULL is preserved");
        return type;
    }


}
