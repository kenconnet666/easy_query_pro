package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import java.time.Duration;
import java.util.Objects;

/**
 * PostgreSQL interval 的三分量值：日历月份、日历天数和微秒时间部分各自保留，不自动归一化。
 *
 * <p>Java equals 比较三分量；数据库的 interval 比较可能认为不同分量表示相等。
 * 例如“一个月”和“三十天”是否比较相等，与它们加到月末日期后是否得到同一天，是两个不同问题。
 * Infinity 独立建模，不能用极大的有限分量冒充。
 */
public interface PgInterval {
    /**
     * 保留服务器的月/日/时间分量，包括合法负值；具体操作可以另行限制其允许的符号组合。
     */
    static Finite of(int months, int days, long microseconds) {
        return new Finite(months, days, microseconds);
    }

    static PgInterval positiveInfinity() {
        return Infinity.POSITIVE;
    }

    static PgInterval negativeInfinity() {
        return Infinity.NEGATIVE;
    }

    static final class Finite implements PgInterval {
        private final int months;
        private final int days;
        private final long microseconds;

        public Finite(int months, int days, long microseconds) {
            if ((months == Integer.MAX_VALUE && days == Integer.MAX_VALUE && microseconds == Long.MAX_VALUE)
                    || (months == Integer.MIN_VALUE && days == Integer.MIN_VALUE && microseconds == Long.MIN_VALUE)) {
                throw new IllegalArgumentException("This component tuple is reserved for PostgreSQL interval infinity");
            }
            this.months = months;
            this.days = days;
            this.microseconds = microseconds;
        }

        /**
         * 仅将纯时间分量转换为固定 Duration。月份长度不固定，自然日还可能跨 DST，不能静默折算。
         * 负微秒采用 floorDiv/floorMod，以满足 Duration 的秒/纳秒规范形式。
         *
         * @throws IllegalStateException 月份或自然日分量非零
         */
        public Duration toFixedDuration() {
            if (months != 0 || days != 0)
                throw new IllegalStateException("A fixed duration requires zero calendar months and days");
            return Duration.ofSeconds(Math.floorDiv(microseconds, 1_000_000), Math.floorMod(microseconds, 1_000_000) * 1_000);
        }

        public int months() {
            return months;
        }

        public int days() {
            return days;
        }

        public long microseconds() {
            return microseconds;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Finite)) return false;
            Finite other = (Finite) o;
            return months == other.months
                    && days == other.days
                    && microseconds == other.microseconds;
        }

        @Override
        public int hashCode() {
            return Objects.hash(months, days, microseconds);
        }

        @Override
        public String toString() {
            return "Finite[months=" + months + ", days=" + days + ", microseconds=" + microseconds + "]";
        }
    }

    enum Infinity implements PgInterval {POSITIVE, NEGATIVE}
}
