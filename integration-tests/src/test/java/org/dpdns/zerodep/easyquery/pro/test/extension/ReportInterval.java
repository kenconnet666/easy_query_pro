package org.dpdns.zerodep.easyquery.pro.test.extension;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.AbstractPgIntervalExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgIntervalExpressions;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

import java.util.function.Function;

/**
 * Consumer-owned extension; inherited arithmetic must retain this concrete type.
 */
public class ReportInterval<T> extends AbstractPgIntervalExpression<T, ReportInterval<T>> {
    public ReportInterval(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
        super(scope, source, expression);
    }

    @Override
    protected ReportInterval<T> recreate(PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
        return new ReportInterval<>(scope, source, expression);
    }

    public BooleanTypeExpression<Boolean> isZero() {
        return equalTo(PgIntervalExpressions.value(scope, PgInterval.of(0, 0, 0)));
    }
}
