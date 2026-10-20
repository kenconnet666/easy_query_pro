package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PgDateBinDslTest {
    static Stream<DecimalDslTest.Backend> postgres() {
        return DecimalDslTest.backends().filter(backend -> backend.dialect() == SqlDialect.POSTGRESQL);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void fixedBinsUseBoundIntervalsAndKeepOriginalQueryParameters(DecimalDslTest.Backend backend) {
        var source = backend.client().queryable(TestEvent.class).where(p -> p.delta().eq(7));
        var origin = LocalDateTime.of(1970, 1, 1, 0, 2, 30);
        if (backend.dialect() != SqlDialect.POSTGRESQL) {
            assertThrows(IllegalArgumentException.class, () -> source.selectColumn(p -> PgSqlDsl.of(p).dateBin(p.occurred(), Duration.ofMinutes(15), origin)));
            return;
        }
        var query = source.selectColumn(p -> PgSqlDsl.of(p).dateBin(p.occurred(), Duration.ofMinutes(15), origin));
        var result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("date_bin(CAST(? AS INTERVAL)"), result.getSQL());
        assertTrue(result.getSQL().contains("TIMESTAMP WITHOUT TIME ZONE"), result.getSQL());
        assertEquals(List.of(PgInterval.of(0, 0, 900_000_000L), origin, 7),
                result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("postgres")
    void invalidStridePrecisionInfinityAndOverflowAreRejected(DecimalDslTest.Backend backend) {
        var source = backend.client().queryable(TestEvent.class);
        var p = source.get1Proxy();
        var pg = PgSqlDsl.of(p);
        var origin = LocalDateTime.of(2024, 1, 1, 0, 0);
        for (var stride : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1),
                Duration.ofSeconds(Long.MAX_VALUE),
                Duration.ofSeconds(Long.MAX_VALUE / 1_000_000L, (Long.MAX_VALUE % 1_000_000L + 1) * 1_000L))) {
            assertThrows(IllegalArgumentException.class, () -> pg.dateBin(p.occurred(), stride, origin));
        }
        assertThrows(IllegalArgumentException.class, () -> pg.dateBin(p.occurred(), Duration.ofSeconds(1), origin.plusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> pg.dateBin(p.occurred(), Duration.ofSeconds(1), LocalDateTime.MIN));
        assertThrows(IllegalArgumentException.class, () -> pg.dateBin(p.occurred(), Duration.ofSeconds(1), LocalDateTime.MAX));
        assertThrows(NullPointerException.class, () -> pg.dateBin(p.occurred(), null, origin));
        assertThrows(NullPointerException.class, () -> pg.dateBin(p.occurred(), Duration.ofSeconds(1), null));
    }
}
