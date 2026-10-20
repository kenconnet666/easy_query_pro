package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;

final class MySqlTemporalTruncation implements TemporalTruncation {
    @Override
    public SQLFunction truncate(ColumnExpression source, TruncationUnit unit, boolean dateOnly) {
        var template = switch (unit) {
            case YEAR -> new Template("MAKEDATE(YEAR({0}),1)", 1);
            case QUARTER ->
                    new Template("DATE_SUB(DATE_SUB(DATE({0}),INTERVAL (DAYOFMONTH({1})-1) DAY),INTERVAL MOD(MONTH({2})-1,3) MONTH)", 3);
            case MONTH -> new Template("DATE_SUB(DATE({0}),INTERVAL (DAYOFMONTH({1})-1) DAY)", 2);
            case ISO_WEEK -> new Template("DATE_SUB(DATE({0}),INTERVAL WEEKDAY({1}) DAY)", 2);
            case DAY -> new Template("DATE({0})", 1);
            case HOUR -> new Template("TIMESTAMP(DATE({0}),MAKETIME(HOUR({1}),0,0))", 2);
            case MINUTE -> new Template("TIMESTAMP(DATE({0}),MAKETIME(HOUR({1}),MINUTE({2}),0))", 3);
            case SECOND -> new Template("TIMESTAMP(DATE({0}),MAKETIME(HOUR({1}),MINUTE({2}),SECOND({3})))", 4);
        };
        return (dateOnly || !unit.isDateUnit() ? template : template.castTo("DATETIME")).bind(source);
    }
}
