package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PgIntervalCodecTest {
    @Test
    void mixedSignsAndSqlStandardLeadingSignRetainAllComponents() throws SQLException {
        PgInterval.Finite expected = PgInterval.of(-14, 3, -14_706_123_456L);
        for (String value : Arrays.asList("-1 years -2 mons +3 days -04:05:06.123456", "@ 1 year 2 mons -3 days 4 hours 5 mins 6.123456 secs ago",
                "-1-2 +3 -4:05:06.123456", "P-1Y-2M3DT-4H-5M-6.123456S")) {
            assertEquals(expected, PgIntervalCodec.decode(value));
        }
        assertEquals(PgInterval.of(0, -3, -14_706_123_456L), PgIntervalCodec.decode("-3 4:05:06.123456"));
        assertEquals(PgInterval.of(-2, 0, 0), PgIntervalCodec.decode("-0-2"));
        for (String zero : Arrays.asList("00:00:00", "@ 0", "0", "PT0S"))
            assertEquals(PgInterval.of(0, 0, 0), PgIntervalCodec.decode(zero));
    }

    @Test
    void extremesInfinityAndMalformedValuesAreExplicit() throws SQLException {
        assertEquals(PgInterval.of(0, 0, Long.MAX_VALUE), PgIntervalCodec.decode("2562047788:00:54.775807"));
        assertEquals(PgInterval.of(0, 0, Long.MIN_VALUE), PgIntervalCodec.decode("PT-2562047788H-54.775808S"));
        assertSame(PgInterval.Infinity.POSITIVE, PgIntervalCodec.decode("infinity"));
        assertSame(PgInterval.Infinity.NEGATIVE, PgIntervalCodec.decode("-infinity"));
        assertThrows(IllegalArgumentException.class, () -> PgInterval.of(Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE));
        for (String invalid : Arrays.asList("PT", "P1Ygarbage", "some input", "2562047788:00:54.775808", "@ 1 sec junk")) {
            assertThrows(SQLException.class, () -> PgIntervalCodec.decode(invalid), invalid);
        }
        assertEquals("-14 months +3 days +1 microseconds", PgIntervalCodec.encode(PgInterval.of(-14, 3, 1)));
    }

    @Test
    void fixedDurationConversionDoesNotInventMonthOrDayLengths() {
        assertEquals(Duration.ofNanos(-1000), PgInterval.of(0, 0, -1).toFixedDuration());
        assertEquals(Duration.ofSeconds(-9_223_372_036_855L, 224_192_000), PgInterval.of(0, 0, Long.MIN_VALUE).toFixedDuration());
        assertThrows(IllegalStateException.class, () -> PgInterval.of(1, 0, 0).toFixedDuration());
        assertThrows(IllegalStateException.class, () -> PgInterval.of(0, 1, 0).toFixedDuration());
        assertNotEquals(PgInterval.of(1, 0, 0), PgInterval.of(0, 30, 0));
    }

    @Test
    void binaryRepresentationPreservesAllThreeComponentsAndInfinity() throws SQLException {
        byte[] bytes = ByteBuffer.allocate(16).putLong(Long.MIN_VALUE).putInt(7).putInt(-3).array();
        assertEquals(PgInterval.of(-3, 7, Long.MIN_VALUE), PgIntervalCodec.decodeBinary(bytes));
        assertSame(PgInterval.Infinity.POSITIVE, PgIntervalCodec.decodeBinary(ByteBuffer.allocate(16)
                .putLong(Long.MAX_VALUE).putInt(Integer.MAX_VALUE).putInt(Integer.MAX_VALUE).array()));
        assertThrows(SQLException.class, () -> PgIntervalCodec.decodeBinary(new byte[15]));
    }
}
