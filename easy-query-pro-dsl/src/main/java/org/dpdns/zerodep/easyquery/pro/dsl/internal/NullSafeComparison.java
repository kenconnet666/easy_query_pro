package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import java.util.Objects;

/**
 * Trusted dialect templates; each operand occurs exactly once.
 */
public record NullSafeComparison(String equal, String distinct) {
    public NullSafeComparison {
        Objects.requireNonNull(equal, "equal");
        Objects.requireNonNull(distinct, "distinct");
    }
}
