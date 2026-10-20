package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.DateTimeTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.DateTimeTypeExpressionImpl;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.MappedValueExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.function.Function;

/**
 * Internal interval operations using the existing function, ownership and result-mapping chain.
 */
public final class PgIntervalExpressions {
    private PgIntervalExpressions() {
    }

    public static <T> PgIntervalExpression<T> view(ExpressionScope scope, PropTypeColumn<T> source) {
        scope.requireSource(source);
        if (!(source instanceof DSLSQLFunctionAvailable) && source.getValue() != null && source.getTable() != null) {
            var column = source.getTable().getEntityMetadata().getColumnNotNull(source.getValue());
            var field = EasyClassUtil.getAllFieldsMap(source.getTable().getEntityClass()).get(column.getFieldName());
            var annotation = field == null ? null : field.getAnnotation(Column.class);
            if (column.getColumnValueSQLConverter() == null && annotation != null && !annotation.dbType().isBlank()
                    && !annotation.dbType().trim().toUpperCase(Locale.ROOT).matches("INTERVAL(?:\\s.*|\\(.*)?")) {
                throw new IllegalArgumentException("Column " + column.getPropertyName() + " does not declare INTERVAL; use an explicit CAST");
            }
        }
        return new Expression<>(scope, source, factory -> scope.operand(source, factory));
    }

    public static PgIntervalExpression<PgInterval> value(ExpressionScope scope, PgInterval value) {
        var source = new AnyTypeExpressionImpl<PgInterval>(scope.context(), scope.anchor(), null,
                factory -> factory.anySQLFunction("CAST({0} AS INTERVAL)", args -> args.value(value)), PgInterval.class);
        return new Expression<>(scope, source, factory -> scope.operand(source, factory));
    }

    public static PgIntervalExpression<PgInterval> cast(ExpressionScope scope, PropTypeColumn<?> source) {
        scope.requireSource(source);
        var cast = new AnyTypeExpressionImpl<PgInterval>(scope.context(), source.getTable(), null,
                factory -> factory.anySQLFunction("CAST({0} AS INTERVAL)", args -> args.sqlFunc(scope.operand(source, factory))), PgInterval.class);
        return new Expression<>(scope, cast, factory -> scope.operand(cast, factory));
    }

    private static final class Expression<T> extends MappedValueExpression<T> implements PgIntervalExpression<T> {

        private Expression(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> function) {
            super(scope, source, function);
            if (scope.dialect() != SqlDialect.POSTGRESQL)
                throw new IllegalArgumentException("INTERVAL requires PostgreSQL");
        }

        @Override
        public PgIntervalExpression<T> plus(PgIntervalExpression<?> other) {
            return arithmetic(other, "+");
        }

        @Override
        public PgIntervalExpression<T> minus(PgIntervalExpression<?> other) {
            return arithmetic(other, "-");
        }

        private PgIntervalExpression<T> arithmetic(PgIntervalExpression<?> other, String operator) {
            scope.requireSource(other);
            return new Expression<>(scope, this, factory -> binary(factory, other, operator));
        }

        private SQLFunction binary(SQLFunc factory, PgIntervalExpression<?> other, String operator) {
            return factory.anySQLFunction("({0} " + operator + " {1})", args ->
                    args.sqlFunc(func().apply(factory)).sqlFunc(scope.operand(other, factory)));
        }

        @Override
        public PgIntervalExpression<T> negated() {
            return new Expression<>(scope, this, factory -> factory.anySQLFunction("(-{0})", args -> args.sqlFunc(func().apply(factory))));
        }

        @Override
        public BooleanTypeExpression<Boolean> equalTo(PgIntervalExpression<?> other) {
            return comparison(other, "=");
        }

        @Override
        public BooleanTypeExpression<Boolean> lessThan(PgIntervalExpression<?> other) {
            return comparison(other, "<");
        }

        private BooleanTypeExpression<Boolean> comparison(PgIntervalExpression<?> other, String operator) {
            scope.requireSource(other);
            return new BooleanTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.function(binary(factory, other, operator), AggregationType.UNKNOWN, true, getTable(), other.getTable()), Boolean.class);
        }

        @Override
        public DateTimeTypeExpression<LocalDateTime> addToDate(PropTypeColumn<LocalDate> date) {
            return calendar(date, "+");
        }

        @Override
        public DateTimeTypeExpression<LocalDateTime> subtractFromDate(PropTypeColumn<LocalDate> date) {
            return calendar(date, "-");
        }

        @Override
        public DateTimeTypeExpression<LocalDateTime> addToDateTime(PropTypeColumn<LocalDateTime> time) {
            return calendar(time, "+");
        }

        @Override
        public DateTimeTypeExpression<LocalDateTime> subtractFromDateTime(PropTypeColumn<LocalDateTime> time) {
            return calendar(time, "-");
        }

        private DateTimeTypeExpression<LocalDateTime> calendar(PropTypeColumn<?> time, String operator) {
            scope.requireSource(time);
            return new DateTimeTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.function(factory.anySQLFunction("({0} " + operator + " {1})", args ->
                                    args.sqlFunc(scope.operand(time, factory)).sqlFunc(func().apply(factory))),
                            AggregationType.UNKNOWN, true, getTable(), time.getTable()), LocalDateTime.class);
        }
    }
}
