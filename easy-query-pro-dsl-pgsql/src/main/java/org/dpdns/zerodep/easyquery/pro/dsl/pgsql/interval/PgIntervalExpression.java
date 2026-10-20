package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.DateTimeTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface PgIntervalExpression<T> extends PropTypeColumn<T>, DSLSQLFunctionAvailable {
    PgIntervalExpression<T> plus(PgIntervalExpression<?> other);

    PgIntervalExpression<T> minus(PgIntervalExpression<?> other);

    PgIntervalExpression<T> negated();

    BooleanTypeExpression<Boolean> equalTo(PgIntervalExpression<?> other);

    BooleanTypeExpression<Boolean> lessThan(PgIntervalExpression<?> other);

    DateTimeTypeExpression<LocalDateTime> addToDate(PropTypeColumn<LocalDate> date);

    DateTimeTypeExpression<LocalDateTime> subtractFromDate(PropTypeColumn<LocalDate> date);

    DateTimeTypeExpression<LocalDateTime> addToDateTime(PropTypeColumn<LocalDateTime> timestamp);

    DateTimeTypeExpression<LocalDateTime> subtractFromDateTime(PropTypeColumn<LocalDateTime> timestamp);
}
