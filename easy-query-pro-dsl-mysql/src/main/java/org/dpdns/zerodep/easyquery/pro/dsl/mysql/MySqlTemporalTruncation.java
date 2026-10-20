package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;

final class MySqlTemporalTruncation implements TemporalTruncation {
    @Override
    public String template(TruncationUnit unit, boolean dateOnly) {
        var template = switch (unit) {
            case YEAR -> "MAKEDATE(YEAR({0}),1)";
            case QUARTER ->
                    "DATE_SUB(DATE_SUB(DATE({0}),INTERVAL (DAYOFMONTH({0})-1) DAY),INTERVAL MOD(MONTH({0})-1,3) MONTH)";
            case MONTH -> "DATE_SUB(DATE({0}),INTERVAL (DAYOFMONTH({0})-1) DAY)";
            case ISO_WEEK -> "DATE_SUB(DATE({0}),INTERVAL WEEKDAY({0}) DAY)";
            case DAY -> "DATE({0})";
            case HOUR -> "TIMESTAMP(DATE({0}),MAKETIME(HOUR({0}),0,0))";
            case MINUTE -> "TIMESTAMP(DATE({0}),MAKETIME(HOUR({0}),MINUTE({0}),0))";
            case SECOND -> "TIMESTAMP(DATE({0}),MAKETIME(HOUR({0}),MINUTE({0}),SECOND({0})))";
        };
        return dateOnly || !unit.isDateUnit() ? template : "CAST(" + template + " AS DATETIME)";
    }
}
