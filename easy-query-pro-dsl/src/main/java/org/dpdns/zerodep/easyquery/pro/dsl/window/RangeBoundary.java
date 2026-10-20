package org.dpdns.zerodep.easyquery.pro.dsl.window;

import java.util.Objects;

/**
 * 原生 RANGE 的单侧边界。偏移量的含义由具体方言规格决定：可以是数值距离，也可以是日历区间。
 *
 * <p>这里保留 {@code 0 PRECEDING}/{@code 0 FOLLOWING} 的原始类别，不归一化为 CURRENT ROW。
 * 数据库先按类别检查语法，再按偏移量计算区间；提前归一化会把合法空窗口误判为非法组合。
 * 偏移量通过方言 frame 绑定为参数，不作为任意 SQL 文本拼接。
 *
 * @param kind   边界类别；允许的起止搭配由 RangeFrame 校验
 * @param offset 仅 PRECEDING/FOLLOWING 必须提供，其他类别必须为 null
 * @param <O>    方言要求的偏移类型；例如整数、BigDecimal、PgInterval.Finite
 */
public record RangeBoundary<O>(Kind kind, O offset) {
    public enum Kind {UNBOUNDED_PRECEDING, PRECEDING, CURRENT_ROW, FOLLOWING, UNBOUNDED_FOLLOWING}

    public RangeBoundary {
        Objects.requireNonNull(kind, "kind");
        boolean finite = kind == Kind.PRECEDING || kind == Kind.FOLLOWING;
        if (finite != (offset != null))
            throw new IllegalArgumentException("Only finite RANGE boundaries require an offset");
    }

    /**
     * 是否需要方言提供偏移量的类型校验和参数绑定。
     */
    public boolean hasOffset() {
        return offset != null;
    }

    public static <O> RangeBoundary<O> unboundedPreceding() {
        return new RangeBoundary<>(Kind.UNBOUNDED_PRECEDING, null);
    }

    /**
     * 排序方向上的前方距离；降序时由数据库按排序方向解释，不由调用方交换边界。
     */
    public static <O> RangeBoundary<O> preceding(O offset) {
        return new RangeBoundary<>(Kind.PRECEDING, Objects.requireNonNull(offset, "offset"));
    }

    public static <O> RangeBoundary<O> currentRow() {
        return new RangeBoundary<>(Kind.CURRENT_ROW, null);
    }

    /**
     * 排序方向上的后方距离；零值仍然保留 FOLLOWING 类别。
     */
    public static <O> RangeBoundary<O> following(O offset) {
        return new RangeBoundary<>(Kind.FOLLOWING, Objects.requireNonNull(offset, "offset"));
    }

    public static <O> RangeBoundary<O> unboundedFollowing() {
        return new RangeBoundary<>(Kind.UNBOUNDED_FOLLOWING, null);
    }
}
