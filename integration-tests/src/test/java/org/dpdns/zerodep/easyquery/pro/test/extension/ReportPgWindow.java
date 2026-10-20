package org.dpdns.zerodep.easyquery.pro.test.extension;

import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.window.PgWindowSpec;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RangeBoundary;

public class ReportPgWindow extends PgWindowSpec<ReportPgWindow> {
    public ReportPgWindow(ExpressionScope scope) {
        super(scope);
    }

    protected ReportPgWindow(ExpressionScope scope, State state) {
        super(scope, state);
    }

    @Override
    protected ReportPgWindow recreate(State state) {
        return new ReportPgWindow(scope, state);
    }

    public ReportPgWindow calendarDays(int days) {
        return rangeIntervalBetween(RangeBoundary.preceding(PgInterval.of(0, days, 0)), RangeBoundary.currentRow());
    }
}
