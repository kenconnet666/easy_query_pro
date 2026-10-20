package org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.DateTimeTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MySQL TIME 的有符号时长操作，支持负值和超过 24 小时的值，不能用 LocalTime 代替。
 * typed value 按声明 fsp 预先检查精度；数据库内加减和 nativeCast 仍保留服务器的边界、警告及错误行为。
 * 整值运算保留 T 的 reader，日期运算和秒数转换返回相应标量类型。
 */
public interface MySqlDurationExpression<T> extends PropTypeColumn<T>, DSLSQLFunctionAvailable {
    MySqlDurationExpression<T> plus(MySqlDurationExpression<?> other);

    MySqlDurationExpression<T> minus(MySqlDurationExpression<?> other);

    MySqlDurationExpression<T> negated();

    BooleanTypeExpression<Boolean> equalTo(MySqlDurationExpression<?> other);

    BooleanTypeExpression<Boolean> lessThan(MySqlDurationExpression<?> other);

    /**
     * 返回包含符号与微秒部分的精确 BigDecimal 秒数；SQL NULL 保留为 null。
     */
    NumberTypeExpression<BigDecimal> toSeconds();

    DateTimeTypeExpression<LocalDateTime> addToDateTime(PropTypeColumn<LocalDateTime> timestamp);

    DateTimeTypeExpression<LocalDateTime> subtractFromDateTime(PropTypeColumn<LocalDateTime> timestamp);
}
