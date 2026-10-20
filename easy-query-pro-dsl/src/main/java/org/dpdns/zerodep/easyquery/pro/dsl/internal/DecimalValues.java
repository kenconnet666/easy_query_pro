package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact value preparation shared by portable and native precision/scale descriptors. */
public final class DecimalValues {
    private DecimalValues() { }

    public static BigDecimal prepare(BigDecimal value, int precision, int scale) {
        if (value == null) return null;
        if (value.signum() == 0) return BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
        long integerDigits = (long) value.precision() - value.scale();
        if (integerDigits > (long) precision - scale || value.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException("Value is not exactly representable as DECIMAL(" + precision + "," + scale + ")");
        }
        // Strip redundant zeros before rescaling so extreme input scales cannot trigger unbounded expansion.
        return value.stripTrailingZeros().setScale(scale, RoundingMode.UNNECESSARY);
    }
}
