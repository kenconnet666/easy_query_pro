package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;

final class HanaTemporalTruncation implements TemporalTruncation {
    @Override
    public String template(TruncationUnit unit, boolean dateOnly) {
        String template;
        switch (unit) {
            case YEAR: template = "ADD_YEARS(DATE'0001-01-01',YEAR({0})-1)"; break;
            case QUARTER: template = "ADD_MONTHS(DATE'0001-01-01',(YEAR({0})-1)*12+FLOOR((MONTH({0})-1)/3)*3)"; break;
            case MONTH: template = "ADD_MONTHS(DATE'0001-01-01',(YEAR({0})-1)*12+MONTH({0})-1)"; break;
            case ISO_WEEK: template = "ADD_DAYS(TO_DATE({0}),-WEEKDAY({0}))"; break;
            case DAY: template = "TO_DATE({0})"; break;
            case HOUR: template = "ADD_SECONDS(CAST(TO_DATE({0}) AS TIMESTAMP),HOUR({0})*3600)"; break;
            case MINUTE: template = "ADD_SECONDS(CAST(TO_DATE({0}) AS TIMESTAMP),HOUR({0})*3600+MINUTE({0})*60)"; break;
            case SECOND: template = "ADD_SECONDS(CAST(TO_DATE({0}) AS TIMESTAMP),HOUR({0})*3600+MINUTE({0})*60+FLOOR(SECOND({0})))"; break;
            default: throw new IllegalStateException("Unhandled value: " + unit);
        }
        return dateOnly || !unit.isDateUnit() ? template : "CAST(" + template + " AS TIMESTAMP)";
    }
}
