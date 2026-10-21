package org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.*;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.DateTimeTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.MappedValueExpression;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import com.easy.query.core.metadata.ColumnMetadata;
import java.lang.reflect.Field;
import com.easy.query.core.util.EasyStringUtil;

/**
 * Internal TIME duration operations, preserving the existing client, transaction and result mapping.
 */
public final class MySqlDurationExpressions {
    private MySqlDurationExpressions() {
    }

    public static <T> MySqlDurationExpression<T> view(ExpressionScope scope, PropTypeColumn<T> source, MySqlDurationType type) {
        Objects.requireNonNull(type, "type");
        scope.requireSource(source);
        if (source.getPropertyType() == LocalTime.class || source.getPropertyType() == java.sql.Time.class) {
            throw new IllegalArgumentException("A clock-time mapping cannot represent general durations; use nativeCast to Duration");
        }
        if (!(source instanceof DSLSQLFunctionAvailable) && source.getValue() != null && source.getTable() != null) {
            ColumnMetadata column = source.getTable().getEntityMetadata().getColumnNotNull(source.getValue());
            Field field = EasyClassUtil.getAllFieldsMap(source.getTable().getEntityClass()).get(column.getFieldName());
            Column annotation = field == null ? null : field.getAnnotation(Column.class);
            if (column.getColumnValueSQLConverter() == null && annotation != null && !EasyStringUtil.isBlank(annotation.dbType())) {
                String declared = annotation.dbType().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
                String expected = type.ddlType(scope.dialect());
                if (!declared.equals(expected) && !(declared.equals("TIME") && type.fractionalPrecision() == 0)) {
                    throw new IllegalArgumentException("Column " + column.getPropertyName() + " declares " + declared + "; use an explicit " + expected + " CAST");
                }
            }
        }
        return new Expression<>(scope, source, factory -> scope.operand(source, factory));
    }

    public static MySqlDurationExpression<Duration> value(ExpressionScope scope, Duration value, MySqlDurationType type) {
        Duration checked = Objects.requireNonNull(type, "type").prepareValue(value);
        String template = type.castTemplate(scope.dialect());
        AnyTypeExpressionImpl<Duration> source = new AnyTypeExpressionImpl<Duration>(scope.context(), scope.anchor(), null,
                factory -> scope.function(factory.anySQLFunction(template, args -> args.value(checked)), AggregationType.UNKNOWN, false), Duration.class);
        return new Expression<>(scope, source, factory -> scope.operand(source, factory));
    }

    public static MySqlDurationExpression<Duration> cast(ExpressionScope scope, PropTypeColumn<?> source, MySqlDurationType type) {
        scope.requireSource(source);
        String template = Objects.requireNonNull(type, "type").castTemplate(scope.dialect());
        AnyTypeExpressionImpl<Duration> cast = new AnyTypeExpressionImpl<Duration>(scope.context(), source.getTable(), null,
                factory -> scope.scalar(factory, source, value -> factory.anySQLFunction(template, args -> args.sqlFunc(value))), Duration.class);
        return new Expression<>(scope, cast, factory -> scope.operand(cast, factory));
    }

    private static final class Expression<T> extends MappedValueExpression<T> implements MySqlDurationExpression<T> {
        private Expression(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> function) {
            super(scope, source, function);
            if (scope.dialect() != SqlDialect.MYSQL) throw new IllegalArgumentException("TIME duration requires MySQL");
        }

        @Override
        public MySqlDurationExpression<T> plus(MySqlDurationExpression<?> other) {
            return arithmetic(other, "ADDTIME");
        }

        @Override
        public MySqlDurationExpression<T> minus(MySqlDurationExpression<?> other) {
            return arithmetic(other, "SUBTIME");
        }

        private MySqlDurationExpression<T> arithmetic(MySqlDurationExpression<?> other, String name) {
            scope.requireSource(other);
            return new Expression<>(scope, this, factory -> scope.scalar(factory, this, other, (left, right) -> factory.anySQLFunction(name + "({0},{1})", args ->
                    args.sqlFunc(left).sqlFunc(right))));
        }

        @Override
        public MySqlDurationExpression<T> negated() {
            return new Expression<>(scope, this, factory -> scope.scalar(factory, this, value -> factory.anySQLFunction("SUBTIME(CAST('00:00:00' AS TIME(6)),{0})", args -> args.sqlFunc(value))));
        }

        @Override
        public BooleanTypeExpression<Boolean> equalTo(MySqlDurationExpression<?> other) {
            return compare(other, "=");
        }

        @Override
        public BooleanTypeExpression<Boolean> lessThan(MySqlDurationExpression<?> other) {
            return compare(other, "<");
        }

        private BooleanTypeExpression<Boolean> compare(MySqlDurationExpression<?> other, String operator) {
            scope.requireSource(other);
            return new BooleanTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.scalar(factory, this, other, (left, right) -> factory.anySQLFunction("({0} " + operator + " {1})", args ->
                            args.sqlFunc(left).sqlFunc(right))), Boolean.class);
        }

        @Override
        public NumberTypeExpression<BigDecimal> toSeconds() {
            // 当前 MySQL 路径的 TIME_TO_SEC 只给整数秒，另加带符号微秒；用乘法避免 div_precision_increment 舍入。
            return new NumberTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.scalar(factory, this, value -> factory.anySQLFunction("(TIME_TO_SEC({0}) + SIGN({0}) * MICROSECOND({0}) * 0.000001)", args -> args.sqlFunc(value))), BigDecimal.class);
        }

        @Override
        public DateTimeTypeExpression<LocalDateTime> addToDateTime(PropTypeColumn<LocalDateTime> time) {
            return dateTime(time, "ADDTIME");
        }

        @Override
        public DateTimeTypeExpression<LocalDateTime> subtractFromDateTime(PropTypeColumn<LocalDateTime> time) {
            return dateTime(time, "SUBTIME");
        }

        private DateTimeTypeExpression<LocalDateTime> dateTime(PropTypeColumn<LocalDateTime> time, String name) {
            scope.requireSource(time);
            return new DateTimeTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.scalar(factory, time, this, (date, duration) -> factory.anySQLFunction(name + "({0},{1})", args -> args.sqlFunc(date).sqlFunc(duration))), LocalDateTime.class);
        }
    }
}
