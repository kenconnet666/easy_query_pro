package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;

final class HanaTemporalTruncation implements TemporalTruncation {
    @Override
    public SQLFunction truncate(ColumnExpression source, TruncationUnit unit, boolean dateOnly) {
        var template = switch (unit) {
            case YEAR -> new Template("ADD_YEARS(DATE'0001-01-01',YEAR({0})-1)", 1);
            // 不能减 DAYOFMONTH-1：跨过 1582 年历法缺口时会落到上一个月。
            case QUARTER -> new Template("ADD_MONTHS(DATE'0001-01-01',(YEAR({0})-1)*12+FLOOR((MONTH({1})-1)/3)*3)", 2);
            case MONTH -> new Template("ADD_MONTHS(DATE'0001-01-01',(YEAR({0})-1)*12+MONTH({1})-1)", 2);
            case ISO_WEEK -> new Template("ADD_DAYS(TO_DATE({0}),-WEEKDAY({1}))", 2);
            case DAY -> new Template("TO_DATE({0})", 1);
            case HOUR -> new Template("ADD_SECONDS(CAST(TO_DATE({0}) AS TIMESTAMP),HOUR({1})*3600)", 2);
            case MINUTE ->
                    new Template("ADD_SECONDS(CAST(TO_DATE({0}) AS TIMESTAMP),HOUR({1})*3600+MINUTE({2})*60)", 3);
            case SECOND ->
                    new Template("ADD_SECONDS(CAST(TO_DATE({0}) AS TIMESTAMP),HOUR({1})*3600+MINUTE({2})*60+FLOOR(SECOND({3})))", 4);
        };
        return (dateOnly || !unit.isDateUnit() ? template : template.castTo("TIMESTAMP")).bind(source);
    }
}
