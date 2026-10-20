package org.dpdns.zerodep.easyquery.pro.test.extension;

import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RowBoundary;
import org.dpdns.zerodep.easyquery.pro.dsl.window.WindowSpec;

/**
 * Consumer-owned window methods remain available after common partition/order/frame operations.
 */
public class ReportWindow extends WindowSpec<ReportWindow> {
    public ReportWindow(ExpressionScope scope) {
        super(scope);
    }

    protected ReportWindow(ExpressionScope scope, State state) {
        super(scope, state);
    }

    @Override
    protected ReportWindow recreate(State state) {
        return new ReportWindow(scope, state);
    }

    public ReportWindow trailing(int rows) {
        return rowsBetween(RowBoundary.preceding(rows), RowBoundary.currentRow());
    }
}
