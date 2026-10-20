package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.DateTimeTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 原生 interval 运算，保持左值 T 的业务映射和读取器。
 * 右值可以使用不同 Java 包装，但必须属于同一 client 的 interval 表达式。
 * SQL NULL 与数据库算术错误沿用原生语义，不能把错误转换成零区间或 NULL。
 *
 * @param <T> 整列 interval 的业务表示
 */
public interface PgIntervalExpression<T> extends PropTypeColumn<T>, DSLSQLFunctionAvailable {
    PgIntervalExpression<T> plus(PgIntervalExpression<?> other);

    PgIntervalExpression<T> minus(PgIntervalExpression<?> other);

    PgIntervalExpression<T> negated();

    BooleanTypeExpression<Boolean> equalTo(PgIntervalExpression<?> other);

    BooleanTypeExpression<Boolean> lessThan(PgIntervalExpression<?> other);

    /**
     * DATE 加 interval 得到时间戳，因此返回 LocalDateTime，不继续返回 interval 或 LocalDate。
     */
    DateTimeTypeExpression<LocalDateTime> addToDate(PropTypeColumn<LocalDate> date);

    DateTimeTypeExpression<LocalDateTime> subtractFromDate(PropTypeColumn<LocalDate> date);

    DateTimeTypeExpression<LocalDateTime> addToDateTime(PropTypeColumn<LocalDateTime> timestamp);

    DateTimeTypeExpression<LocalDateTime> subtractFromDateTime(PropTypeColumn<LocalDateTime> timestamp);
}
