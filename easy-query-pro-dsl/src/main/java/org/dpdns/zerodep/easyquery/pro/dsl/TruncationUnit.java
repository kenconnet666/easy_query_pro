package org.dpdns.zerodep.easyquery.pro.dsl;

/**
 * Calendar boundaries; ISO_WEEK always starts on Monday.
 */
public enum TruncationUnit {
    YEAR, QUARTER, MONTH, ISO_WEEK, DAY, HOUR, MINUTE, SECOND;

    public boolean isDateUnit() {
        return switch (this) {
            case YEAR, QUARTER, MONTH, ISO_WEEK, DAY -> true;
            default -> false;
        };
    }
}
