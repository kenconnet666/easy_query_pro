package org.dpdns.zerodep.easyquery.pro.dsl.window;

import java.util.Objects;

/**
 * 三库共同 ROWS 的单侧边界，偏移量表示物理行数。
 *
 * <p>行数是受控结构参数，先验证非负整数再按 SQL 语法输出；不接受外部 SQL 文本。
 * 共同规则将零行偏移归一化为 CURRENT ROW。原生 RANGE 的类别校验不同，不能复用此归一化。
 */
public final class RowBoundary {
    private final Kind kind;
    private final int offset;

    public enum Kind {UNBOUNDED_PRECEDING, PRECEDING, CURRENT_ROW, FOLLOWING, UNBOUNDED_FOLLOWING}

    /**
     * @param kind   起点/终点的边界类别
     * @param offset 仅有限 PRECEDING/FOLLOWING 使用的非负行数
     */
    public RowBoundary(Kind kind, int offset) {
        Objects.requireNonNull(kind, "kind");
        if (offset < 0) throw new IllegalArgumentException("A ROWS offset cannot be negative");
        if (kind != Kind.PRECEDING && kind != Kind.FOLLOWING && offset != 0)
            throw new IllegalArgumentException("Only PRECEDING/FOLLOWING accepts an offset");
        if ((kind == Kind.PRECEDING || kind == Kind.FOLLOWING) && offset == 0) kind = Kind.CURRENT_ROW;
        this.kind = kind;
        this.offset = offset;
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
        switch (kind) {
            case UNBOUNDED_PRECEDING: return Long.MIN_VALUE;
            case PRECEDING: return -(long) offset;
            case CURRENT_ROW: return 0;
            case FOLLOWING: return offset;
            case UNBOUNDED_FOLLOWING: return Long.MAX_VALUE;
        }
        throw new IllegalStateException("Unhandled value: " + kind);
    }

    public String sql() {
        switch (kind) {
            case PRECEDING: return offset + " PRECEDING";
            case FOLLOWING: return offset + " FOLLOWING";
            default: return kind.name().replace('_', ' ');
        }
    }

    public Kind kind() {
        return kind;
    }

    public int offset() {
        return offset;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RowBoundary)) return false;
        RowBoundary other = (RowBoundary) o;
        return Objects.equals(kind, other.kind)
                && offset == other.offset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, offset);
    }

    @Override
    public String toString() {
        return "RowBoundary[kind=" + kind + ", offset=" + offset + "]";
    }
}
