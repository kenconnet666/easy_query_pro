package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import java.time.Duration;

/**
 * PostgreSQL's calendar months, calendar days and microsecond time are independent components.
 */
public sealed interface PgInterval permits PgInterval.Finite, PgInterval.Infinity {
    static Finite of(int months, int days, long microseconds) {
        return new Finite(months, days, microseconds);
    }

    static PgInterval positiveInfinity() {
        return Infinity.POSITIVE;
    }

    static PgInterval negativeInfinity() {
        return Infinity.NEGATIVE;
    }

    record Finite(int months, int days, long microseconds) implements PgInterval {
        public Finite {
            if ((months == Integer.MAX_VALUE && days == Integer.MAX_VALUE && microseconds == Long.MAX_VALUE)
                    || (months == Integer.MIN_VALUE && days == Integer.MIN_VALUE && microseconds == Long.MIN_VALUE)) {
                throw new IllegalArgumentException("This component tuple is reserved for PostgreSQL interval infinity");
            }
        }

        /**
         * Calendar days may cross DST and months have variable lengths, so neither is silently converted.
         */
        public Duration toFixedDuration() {
            if (months != 0 || days != 0)
                throw new IllegalStateException("A fixed duration requires zero calendar months and days");
            return Duration.ofSeconds(Math.floorDiv(microseconds, 1_000_000), Math.floorMod(microseconds, 1_000_000) * 1_000);
        }
    }

    enum Infinity implements PgInterval {POSITIVE, NEGATIVE}
}
