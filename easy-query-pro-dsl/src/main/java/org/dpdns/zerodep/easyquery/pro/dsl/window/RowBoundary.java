package org.dpdns.zerodep.easyquery.pro.dsl.window;

import java.util.Objects;

/**
 * A validated ROWS offset, rendered as SQL grammar rather than as a data value.
 */
public record RowBoundary(Kind kind, int offset) {
    public enum Kind {UNBOUNDED_PRECEDING, PRECEDING, CURRENT_ROW, FOLLOWING, UNBOUNDED_FOLLOWING}

    public RowBoundary {
        Objects.requireNonNull(kind, "kind");
        if (offset < 0) throw new IllegalArgumentException("A ROWS offset cannot be negative");
        if (kind != Kind.PRECEDING && kind != Kind.FOLLOWING && offset != 0)
            throw new IllegalArgumentException("Only PRECEDING/FOLLOWING accepts an offset");
        if ((kind == Kind.PRECEDING || kind == Kind.FOLLOWING) && offset == 0) kind = Kind.CURRENT_ROW;
    }

    public static RowBoundary unboundedPreceding() {
        return new RowBoundary(Kind.UNBOUNDED_PRECEDING, 0);
    }

    public static RowBoundary preceding(int rows) {
        return new RowBoundary(Kind.PRECEDING, rows);
    }

    public static RowBoundary currentRow() {
        return new RowBoundary(Kind.CURRENT_ROW, 0);
    }

    public static RowBoundary following(int rows) {
        return new RowBoundary(Kind.FOLLOWING, rows);
    }

    public static RowBoundary unboundedFollowing() {
        return new RowBoundary(Kind.UNBOUNDED_FOLLOWING, 0);
    }

    long position() {
        return switch (kind) {
            case UNBOUNDED_PRECEDING -> Long.MIN_VALUE;
            case PRECEDING -> -(long) offset;
            case CURRENT_ROW -> 0;
            case FOLLOWING -> offset;
            case UNBOUNDED_FOLLOWING -> Long.MAX_VALUE;
        };
    }

    public String sql() {
        return switch (kind) {
            case PRECEDING -> offset + " PRECEDING";
            case FOLLOWING -> offset + " FOLLOWING";
            default -> kind.name().replace('_', ' ');
        };
    }
}
