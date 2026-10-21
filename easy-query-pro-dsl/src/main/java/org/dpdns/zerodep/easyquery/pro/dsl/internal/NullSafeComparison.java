package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import java.util.Objects;

/**
 * Trusted dialect templates; each operand occurs exactly once.
 */
public final class NullSafeComparison {
    private final String equal;
    private final String distinct;

    public NullSafeComparison(String equal, String distinct) {
        Objects.requireNonNull(equal, "equal");
        Objects.requireNonNull(distinct, "distinct");
        this.equal = equal;
        this.distinct = distinct;
    }

    public String equal() {
        return equal;
    }

    public String distinct() {
        return distinct;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NullSafeComparison)) return false;
        NullSafeComparison other = (NullSafeComparison) o;
        return Objects.equals(equal, other.equal)
                && Objects.equals(distinct, other.distinct);
    }

    @Override
    public int hashCode() {
        return Objects.hash(equal, distinct);
    }

    @Override
    public String toString() {
        return "NullSafeComparison[equal=" + equal + ", distinct=" + distinct + "]";
    }
}
