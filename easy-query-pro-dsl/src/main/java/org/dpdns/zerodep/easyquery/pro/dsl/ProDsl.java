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
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExpressionScope;
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
 * Portable additions that compose with EasyQuery's existing query and expression types.
 */
public final class ProDsl {
    private final ExpressionScope scope;
    private final EntitySQLContext context;
    private final TableAvailable anchor;
    private final QueryRuntimeContext runtime;
    private final SqlDialect dialect;

    private ProDsl(ProxyEntity<?, ?> entity) {
        scope = new ExpressionScope(entity);
        context = scope.context();
        anchor = scope.anchor();
        runtime = scope.runtime();
        dialect = scope.dialect();
    }

    public static ProDsl of(ProxyEntity<?, ?> entity) {
        return new ProDsl(entity);
    }

    /**
     * Emits the database's CAST, including its native rounding, overflow and invalid-input behavior.
     */
    public <N extends Number> NumberTypeExpression<N> nativeCast(PropTypeColumn<?> source, NumericType<N> target) {
        Objects.requireNonNull(target, "target");
        Class<N> resultType = boxedTarget(target.javaType());
        return new NumberTypeExpressionImpl<>(context, anchor, null, conversion(source, target), resultType);
    }

    /**
     * Preserves native truncation rules; use value for a losslessly validated text parameter.
     */
    public StringTypeExpression<String> nativeCast(PropTypeColumn<?> source, TextType target) {
        return new StringTypeExpressionImpl<>(context, anchor, null, conversion(source, target), String.class);
    }

    private Function<SQLFunc, SQLFunction> conversion(PropTypeColumn<?> source, CastTarget<?> target) {
        scope.requireSource(source);
        Objects.requireNonNull(target, "target");
        String template = target.castTemplate(dialect);
        return factory -> {
            SQLFunction operand = scope.operand(source, factory);
            var cast = factory.anySQLFunction(template, args -> args.sqlFunc(source.getTable(), operand));
            return scope.operation(cast, operand.getAggregationType(), operand);
        };
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
                scope.function(factory.anySQLFunction(template, args -> args.value(boundValue)), AggregationType.UNKNOWN, false), resultType);
    }

    public <N extends Number> NumberTypeExpression<N> nullValue(NumericType<N> type) {
        return value(null, type);
    }

    public StringTypeExpression<String> value(String value, TextType type) {
        Objects.requireNonNull(type, "type");
        String boundValue = type.prepareValue(value);
        String template = type.castTemplate(dialect);
        return new StringTypeExpressionImpl<>(context, anchor, null, factory ->
                scope.function(factory.anySQLFunction(template, args -> args.value(boundValue)), AggregationType.UNKNOWN, false), String.class);
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
        var access = runtime.getService(JsonPathAccess.class);
        access.requireNavigationSupport();
        return factory -> {
            var operand = scope.operand(document, factory);
            var rooted = factory.anySQLFunction("{0}", args -> args.sqlFunc(document.getTable(), operand));
            var node = access.fragment(factory, rooted, path);
            SQLFunction selected = switch (result) {
                case FRAGMENT -> node;
                case TEXT -> access.scalarText(factory, node);
                case SERIALIZED -> access.serializedText(factory, node);
                case EXISTS ->
                        factory.anySQLFunction("(CASE WHEN {0} IS NOT NULL THEN TRUE ELSE FALSE END)", args -> args.sqlFunc(node));
                case BOOLEAN ->
                        factory.anySQLFunction("(CASE {0} WHEN 'true' THEN TRUE WHEN 'false' THEN FALSE ELSE NULL END)",
                                args -> args.sqlFunc(access.serializedText(factory, node)));
            };
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
            var operand = scope.operand(source, factory);
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
            var leftOperand = scope.operand(left, factory);
            var rightOperand = scope.operand(right, factory);
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
        if (left instanceof ScalarComparisonRestriction restriction)
            throw new IllegalArgumentException(restriction.scalarComparisonError());
        if (right instanceof ScalarComparisonRestriction restriction)
            throw new IllegalArgumentException(restriction.scalarComparisonError());
        var templates = runtime.getService(NullSafeComparison.class);
        String template = distinct ? templates.distinct() : templates.equal();
        return new BooleanTypeExpressionImpl<>(context, anchor, null, factory -> {
            var leftOperand = scope.operand(left, factory);
            var rightOperand = scope.operand(right, factory);
            var result = factory.anySQLFunction(template, args -> {
                args.sqlFunc(left.getTable(), leftOperand);
                args.sqlFunc(right.getTable(), rightOperand);
            });
            return scope.operation(result, AggregationType.UNKNOWN, leftOperand, rightOperand);
        }, Boolean.class);
    }

    private static <N extends Number> Class<N> boxedTarget(Class<N> type) {
        Objects.requireNonNull(type, "target Java type");
        if (type.isPrimitive())
            throw new IllegalArgumentException("Pro expressions require boxed result types so SQL NULL is preserved");
        return type;
    }


}
