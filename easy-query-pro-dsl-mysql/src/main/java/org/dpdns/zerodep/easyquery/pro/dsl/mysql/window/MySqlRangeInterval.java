package org.dpdns.zerodep.easyquery.pro.dsl.mysql.window;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * MySQL 窗口语法中的 {@code INTERVAL amount unit}，用于 DATE/DATETIME 的 RANGE 距离。
 *
 * <p>它是查询边界，不是 TIME 存储值，因此不采用 TIME 的 ±838:59:59 限制。
 * 数量使用 JDBC 参数，单位只从受控 ChronoUnit 集合映射。MONTHS/YEARS 保留日历语义，
 * 不换算为固定天数；固定 Duration 可以通过 {@link #duration(Duration)} 转成精确微秒。
 *
 * @param amount 非负数量；零值仍由 RangeBoundary 保留方向类别
 * @param unit   支持年、月、日、小时、分钟、秒、微秒；其他单位明确拒绝
 */
public record MySqlRangeInterval(long amount, ChronoUnit unit) {
    public MySqlRangeInterval {
        Objects.requireNonNull(unit, "unit");
        if (amount < 0) throw new IllegalArgumentException("A RANGE interval cannot be negative");
        switch (unit) {
            case YEARS, MONTHS, DAYS, HOURS, MINUTES, SECONDS, MICROS -> {
            }
            default -> throw new IllegalArgumentException("Unsupported MySQL RANGE interval unit: " + unit);
        }
    }

    /**
     * 将固定时长精确转换为微秒，不接受亚微秒截断或 long 溢出。
     * 分别计算秒与纳秒部分，避免先调用 toNanos 导致本可用的长时长过早溢出。
     */
    public static MySqlRangeInterval duration(Duration value) {
        Objects.requireNonNull(value, "duration");
        if (value.isNegative() || value.getNano() % 1_000 != 0)
            throw new IllegalArgumentException("A RANGE duration must be nonnegative and exact to microseconds");
        try {
            long micros = Math.addExact(Math.multiplyExact(value.getSeconds(), 1_000_000L), value.getNano() / 1_000L);
            return new MySqlRangeInterval(micros, ChronoUnit.MICROS);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("RANGE duration exceeds signed microseconds", overflow);
        }
    }

    String sqlUnit() {
        return switch (unit) {
            case YEARS -> "YEAR";
            case MONTHS -> "MONTH";
            case DAYS -> "DAY";
            case HOURS -> "HOUR";
            case MINUTES -> "MINUTE";
            case SECONDS -> "SECOND";
            case MICROS -> "MICROSECOND";
            default -> throw new IllegalStateException("Unvalidated RANGE unit");
        };
    }
}
