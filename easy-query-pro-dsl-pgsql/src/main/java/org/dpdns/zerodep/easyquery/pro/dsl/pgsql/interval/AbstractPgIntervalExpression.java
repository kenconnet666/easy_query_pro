package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.DateTimeTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.DateTimeTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.MappedValueExpression;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * Reuses the native interval operations while allowing whole-value chains to retain a concrete subtype.
 */
public abstract class AbstractPgIntervalExpression<T, S extends PgIntervalExpression<T>> extends MappedValueExpression<T> implements PgIntervalExpression<T> {

    protected AbstractPgIntervalExpression(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> function) {
        super(scope, source, function);
        if (scope.dialect() != SqlDialect.POSTGRESQL)
            throw new IllegalArgumentException("INTERVAL requires PostgreSQL");
        PgIntervalExpressions.requireView(scope, source);
    }

    /**
     * Pass the previous expression and supplied SQL callback unchanged so SQL operations and the business reader remain attached.
     */
    protected abstract S recreate(PropTypeColumn<T> mappingSource, Function<SQLFunc, SQLFunction> expression);

    @Override
    public S plus(PgIntervalExpression<?> other) {
        return arithmetic(other, "+");
    }

    @Override
    public S minus(PgIntervalExpression<?> other) {
        return arithmetic(other, "-");
    }

    private S arithmetic(PgIntervalExpression<?> other, String operator) {
        scope.requireSource(other);
        return recreate(this, factory -> binary(factory, other, operator));
    }

    private SQLFunction binary(SQLFunc factory, PgIntervalExpression<?> other, String operator) {
        return scope.scalar(factory, this, other, (left, right) -> factory.anySQLFunction("({0} " + operator + " {1})", args ->
                args.sqlFunc(left).sqlFunc(right)));
    }

    @Override
    public S negated() {
        return recreate(this, factory -> scope.scalar(factory, this, value -> factory.anySQLFunction("(-{0})", args -> args.sqlFunc(value))));
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
                factory -> binary(factory, other, operator), Boolean.class);
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
                factory -> scope.scalar(factory, time, this, (date, interval) -> factory.anySQLFunction("({0} " + operator + " {1})", args ->
                        args.sqlFunc(date).sqlFunc(interval))), LocalDateTime.class);
    }
}
