package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;

final class HanaTemporalTruncation implements TemporalTruncation {
    @Override
    public String template(TruncationUnit unit, boolean dateOnly) {
        var template = switch (unit) {
            case YEAR -> "ADD_YEARS(DATE'0001-01-01',YEAR({0})-1)";
            // 不能减 DAYOFMONTH-1：跨过 1582 年历法缺口时会落到上一个月。
            case QUARTER -> "ADD_MONTHS(DATE'0001-01-01',(YEAR({0})-1)*12+FLOOR((MONTH({0})-1)/3)*3)";
            case MONTH -> "ADD_MONTHS(DATE'0001-01-01',(YEAR({0})-1)*12+MONTH({0})-1)";
            case ISO_WEEK -> "ADD_DAYS(TO_DATE({0}),-WEEKDAY({0}))";
            case DAY -> "TO_DATE({0})";
            case HOUR -> "ADD_SECONDS(CAST(TO_DATE({0}) AS TIMESTAMP),HOUR({0})*3600)";
            case MINUTE -> "ADD_SECONDS(CAST(TO_DATE({0}) AS TIMESTAMP),HOUR({0})*3600+MINUTE({0})*60)";
            case SECOND ->
                    "ADD_SECONDS(CAST(TO_DATE({0}) AS TIMESTAMP),HOUR({0})*3600+MINUTE({0})*60+FLOOR(SECOND({0})))";
        };
        return dateOnly || !unit.isDateUnit() ? template : "CAST(" + template + " AS TIMESTAMP)";
    }
}
