package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.*;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.MappedValueExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Internal implementations behind PgSqlDsl; all rendering and binding stays in the upstream expression chain.
 */
public final class PgArrayExpressions {
    private static final Map<String, String> ALIASES = Map.of("integer", "int4", "int", "int4", "bigint", "int8", "boolean", "bool",
            "decimal", "numeric", "timestamp without time zone", "timestamp");

    private PgArrayExpressions() {
    }

    public static <E, T> PgArrayExpression<E, T> view(ExpressionScope scope, PropTypeColumn<T> source, PgArrayType<E> type) {
        requireType(scope, type);
        scope.requireSource(source);
        if (source instanceof PgArrayExpression<?, ?> known && !type.equals(known.sqlType())) {
            throw new IllegalArgumentException("Use an explicit CAST between different SQL array element types");
        }
        if (!(source instanceof DSLSQLFunctionAvailable) && source.getValue() != null && source.getTable() != null) {
            var column = source.getTable().getEntityMetadata().getColumnNotNull(source.getValue());
            var field = EasyClassUtil.getAllFieldsMap(source.getTable().getEntityClass()).get(column.getFieldName());
            var annotation = field == null ? null : field.getAnnotation(Column.class);
            if (column.getColumnValueSQLConverter() == null && annotation != null && !annotation.dbType().isBlank()) {
                String declared = annotation.dbType().trim().toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", "");
                if (declared.endsWith("[]")) {
                    String base = declared.substring(0, declared.length() - 2).trim();
                    if (!ALIASES.getOrDefault(base, base).equals(type.elementSqlName())) {
                        throw new IllegalArgumentException("Column " + column.getPropertyName() + " declares " + declared + "; use an explicit " + type.sqlName() + " CAST");
                    }
                } else {
                    throw new IllegalArgumentException("An array view requires an explicit array column declaration; found " + declared);
                }
            }
        }
        return new Expression<>(scope, source, type, factory -> scope.operand(source, factory));
    }

    public static <E> PgArrayExpression<E, PgArrayValue<E>> value(ExpressionScope scope, List<? extends E> values, PgArrayType<E> type) {
        requireType(scope, type);
        var parameter = type.value(values);
        var source = new AnyTypeExpressionImpl<PgArrayValue<E>>(scope.context(), scope.anchor(), null,
                factory -> factory.anySQLFunction(type.castTemplate(scope.dialect()), args -> args.value(parameter)), type.javaType());
        return new Expression<>(scope, source, type, factory -> scope.operand(source, factory));
    }

    public static <E> PgArrayExpression<E, PgArrayValue<E>> cast(ExpressionScope scope, PropTypeColumn<?> source, PgArrayType<E> type) {
        requireType(scope, type);
        scope.requireSource(source);
        var cast = new AnyTypeExpressionImpl<PgArrayValue<E>>(scope.context(), source.getTable(), null,
                factory -> factory.anySQLFunction(type.castTemplate(scope.dialect()), args -> args.sqlFunc(scope.operand(source, factory))), type.javaType());
        return new Expression<>(scope, cast, type, factory -> scope.operand(cast, factory));
    }

    private static void requireType(ExpressionScope scope, PgArrayType<?> type) {
        Objects.requireNonNull(type, "type");
        if (scope.dialect() != SqlDialect.POSTGRESQL)
            throw new IllegalArgumentException("SQL arrays require PostgreSQL");
    }

    private static final class Expression<E, T> extends MappedValueExpression<T> implements PgArrayExpression<E, T> {
        private final PgArrayType<E> type;

        private Expression(ExpressionScope scope, PropTypeColumn<T> source, PgArrayType<E> type, Function<SQLFunc, SQLFunction> expression) {
            super(scope, source, expression);
            this.type = type;
        }

        @Override
        public PgArrayType<E> sqlType() {
            return type;
        }

        @Override
        public BooleanTypeExpression<Boolean> anyEq(PropTypeColumn<E> value) {
            scope.requireSource(value);
            return predicate(factory -> factory.anySQLFunction("({0} = ANY({1}))", args ->
                    args.sqlFunc(scope.operand(value, factory)).sqlFunc(func().apply(factory))), value);
        }

        @Override
        public BooleanTypeExpression<Boolean> anyEqValue(E value) {
            Object encoded = type.jdbcElement(type.prepareElement(value));
            return predicate(factory -> factory.anySQLFunction("(CAST({0} AS " + type.elementSqlName() + ") = ANY({1}))", args ->
                    args.value(encoded).sqlFunc(func().apply(factory))), this);
        }

        @Override
        public BooleanTypeExpression<Boolean> containsAll(PgArrayExpression<E, ?> values) {
            return compare(values, "@>");
        }

        @Override
        public BooleanTypeExpression<Boolean> containedBy(PgArrayExpression<E, ?> values) {
            return compare(values, "<@");
        }

        @Override
        public BooleanTypeExpression<Boolean> overlaps(PgArrayExpression<E, ?> values) {
            return compare(values, "&&");
        }

        @Override
        public BooleanTypeExpression<Boolean> containsAllValues(List<? extends E> values) {
            return containsAll(value(scope, values, type));
        }

        @Override
        public BooleanTypeExpression<Boolean> containedByValues(List<? extends E> values) {
            return containedBy(value(scope, values, type));
        }

        @Override
        public BooleanTypeExpression<Boolean> overlapsValues(List<? extends E> values) {
            return overlaps(value(scope, values, type));
        }

        private BooleanTypeExpression<Boolean> compare(PgArrayExpression<E, ?> values, String operator) {
            scope.requireSource(values);
            if (!type.equals(values.sqlType()))
                throw new IllegalArgumentException("Array SQL element types must match");
            return predicate(factory -> factory.anySQLFunction("({0} " + operator + " {1})", args ->
                    args.sqlFunc(func().apply(factory)).sqlFunc(scope.operand(values, factory))), values);
        }

        private BooleanTypeExpression<Boolean> predicate(Function<SQLFunc, SQLFunction> expression, PropTypeColumn<?> other) {
            return new BooleanTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.function(expression.apply(factory), AggregationType.UNKNOWN, true, getTable(), other.getTable()), Boolean.class);
        }

        @Override
        public NumberTypeExpression<Integer> cardinality() {
            return new NumberTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.function(factory.anySQLFunction("cardinality({0})", args -> args.sqlFunc(func().apply(factory))),
                            AggregationType.UNKNOWN, true, getTable()), Integer.class);
        }

        @Override
        public NumberTypeExpression<Integer> dimensionLength(int dimension) {
            if (dimension < 1) throw new IllegalArgumentException("SQL array dimensions are one-based");
            return new NumberTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.function(factory.anySQLFunction("array_length({0},{1})", args -> args.sqlFunc(func().apply(factory)).value(dimension)),
                            AggregationType.UNKNOWN, true, getTable()), Integer.class);
        }

        @Override
        public AnyTypeExpression<E> elementAtSqlIndex(int index) {
            return new AnyTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.function(factory.anySQLFunction("({0})[{1}]", args -> args.sqlFunc(func().apply(factory)).value(index)),
                            AggregationType.UNKNOWN, true, getTable()), type.elementType());
        }
    }
}
