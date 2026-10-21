package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;

final class MySqlTemporalTruncation implements TemporalTruncation {
    @Override
    public String template(TruncationUnit unit, boolean dateOnly) {
        String template;
        switch (unit) {
            case YEAR: template = "MAKEDATE(YEAR({0}),1)"; break;
            case QUARTER: template = "DATE_SUB(DATE_SUB(DATE({0}),INTERVAL (DAYOFMONTH({0})-1) DAY),INTERVAL MOD(MONTH({0})-1,3) MONTH)"; break;
            case MONTH: template = "DATE_SUB(DATE({0}),INTERVAL (DAYOFMONTH({0})-1) DAY)"; break;
            case ISO_WEEK: template = "DATE_SUB(DATE({0}),INTERVAL WEEKDAY({0}) DAY)"; break;
            case DAY: template = "DATE({0})"; break;
            case HOUR: template = "TIMESTAMP(DATE({0}),MAKETIME(HOUR({0}),0,0))"; break;
            case MINUTE: template = "TIMESTAMP(DATE({0}),MAKETIME(HOUR({0}),MINUTE({0}),0))"; break;
            case SECOND: template = "TIMESTAMP(DATE({0}),MAKETIME(HOUR({0}),MINUTE({0}),SECOND({0})))"; break;
            default: throw new IllegalStateException("Unhandled value: " + unit);
        }
        return dateOnly || !unit.isDateUnit() ? template : "CAST(" + template + " AS DATETIME)";
    }
}
