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
import java.util.stream.Stream;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestEventProxy;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.common.ToSQLResult;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PgDateBinDslTest {
    static Stream<DecimalDslTest.Backend> postgres() {
        return DecimalDslTest.backends().filter(backend -> backend.dialect() == SqlDialect.POSTGRESQL);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void fixedBinsUseBoundIntervalsAndKeepOriginalQueryParameters(DecimalDslTest.Backend backend) {
        EntityQueryable<TestEventProxy, TestEvent> source = backend.client().queryable(TestEvent.class).where(p -> p.delta().eq(7));
        LocalDateTime origin = LocalDateTime.of(1970, 1, 1, 0, 2, 30);
        if (backend.dialect() != SqlDialect.POSTGRESQL) {
            assertThrows(IllegalArgumentException.class, () -> source.selectColumn(p -> PgSqlDsl.of(p).dateBin(p.occurred(), Duration.ofMinutes(15), origin)));
            return;
        }
        Query<LocalDateTime> query = source.selectColumn(p -> PgSqlDsl.of(p).dateBin(p.occurred(), Duration.ofMinutes(15), origin));
        ToSQLResult result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("date_bin(CAST(? AS INTERVAL)"), result.getSQL());
        assertTrue(result.getSQL().contains("TIMESTAMP WITHOUT TIME ZONE"), result.getSQL());
        assertEquals(Arrays.asList(PgInterval.of(0, 0, 900_000_000L), origin, 7),
                result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("postgres")
    void invalidStridePrecisionInfinityAndOverflowAreRejected(DecimalDslTest.Backend backend) {
        EntityQueryable<TestEventProxy, TestEvent> source = backend.client().queryable(TestEvent.class);
        TestEventProxy p = source.get1Proxy();
        PgSqlDsl pg = PgSqlDsl.of(p);
        LocalDateTime origin = LocalDateTime.of(2024, 1, 1, 0, 0);
        for (Duration stride : Arrays.asList(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1),
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
