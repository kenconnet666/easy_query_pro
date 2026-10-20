package org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.DateTimeTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Native MySQL TIME arithmetic, including its saturation and warning behavior at the storage bounds.
 */
public interface MySqlDurationExpression<T> extends PropTypeColumn<T>, DSLSQLFunctionAvailable {
    MySqlDurationExpression<T> plus(MySqlDurationExpression<?> other);

    MySqlDurationExpression<T> minus(MySqlDurationExpression<?> other);

    MySqlDurationExpression<T> negated();

    BooleanTypeExpression<Boolean> equalTo(MySqlDurationExpression<?> other);

    BooleanTypeExpression<Boolean> lessThan(MySqlDurationExpression<?> other);

    NumberTypeExpression<BigDecimal> toSeconds();

    DateTimeTypeExpression<LocalDateTime> addToDateTime(PropTypeColumn<LocalDateTime> timestamp);

    DateTimeTypeExpression<LocalDateTime> subtractFromDateTime(PropTypeColumn<LocalDateTime> timestamp);
}
