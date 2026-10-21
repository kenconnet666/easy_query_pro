package org.dpdns.zerodep.easyquery.pro.dsl;

/**
 * Calendar boundaries; ISO_WEEK always starts on Monday.
 */
public enum TruncationUnit {
    YEAR, QUARTER, MONTH, ISO_WEEK, DAY, HOUR, MINUTE, SECOND;

    public boolean isDateUnit() {
        switch (this) {
            case YEAR: case QUARTER: case MONTH: case ISO_WEEK: case DAY: return true;
            default: return false;
        }
    }
}
