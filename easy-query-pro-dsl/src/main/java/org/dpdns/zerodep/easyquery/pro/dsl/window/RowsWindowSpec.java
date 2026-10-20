package org.dpdns.zerodep.easyquery.pro.dsl.window;

import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

/**
 * Common window specification; dialect RANGE support belongs to the corresponding specification subtype.
 */
public class RowsWindowSpec extends WindowSpec<RowsWindowSpec> {
    public RowsWindowSpec(ExpressionScope scope) {
        super(scope);
    }

    protected RowsWindowSpec(ExpressionScope scope, State state) {
        super(scope, state);
    }

    @Override
    protected RowsWindowSpec recreate(State state) {
        return new RowsWindowSpec(scope, state);
    }
}
