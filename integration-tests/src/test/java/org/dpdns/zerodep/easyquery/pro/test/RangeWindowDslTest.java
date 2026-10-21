package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.window.MySqlRangeInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.window.MySqlWindowSpec;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.window.PgWindowSpec;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RangeBoundary;
import org.dpdns.zerodep.easyquery.pro.dsl.window.WindowSpec;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft1Proxy;
import com.easy.query.core.proxy.core.draft.Draft1;
import com.easy.query.core.common.ToSQLResult;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.*;

class RangeWindowDslTest {
    static Stream<DecimalDslTest.Backend> backends() {
        return DecimalDslTest.backends().filter(backend -> backend.dialect() != SqlDialect.HANA);
    }

    @ParameterizedTest
    @MethodSource("backends")
    void offsetsBindInOrderAndKeepZeroCategories(DecimalDslTest.Backend backend) {
        EntityQueryable<Draft1Proxy<BigDecimal>, Draft1<BigDecimal>> selected = backend.client().queryable(TestProduct.class).select(p -> Select.DRAFT.of(ProDsl.of(p).over(p.amount().sumBigDecimal(),
                RangeWindowContract.numeric(p, p.id(), RangeBoundary.following(1), RangeBoundary.following(0), true))));
        ToSQLResult rendered = selected.toSQLResult();
        assertTrue(rendered.getSQL().contains(" FOLLOWING AND "), rendered.getSQL());
        assertEquals(Arrays.asList(1L, 0L), rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertTrue(rendered.getSQL().contains(backend.dialect() == SqlDialect.MYSQL ? "? FOLLOWING" : "CAST(? AS BIGINT) FOLLOWING"));
    }

    @ParameterizedTest
    @MethodSource("backends")
    void invalidOffsetsAndIncompatibleKeysFailBeforeExecution(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery query = backend.client();
        for (Number offset : Arrays.asList(-1, Double.NaN, BigInteger.ONE.shiftLeft(63))) {
            assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).over(p.amount().sum(),
                    RangeWindowContract.numeric(p, p.id(), RangeBoundary.preceding(offset), RangeBoundary.currentRow(), true))).toSQL());
        }
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).over(p.amount().sum(),
                RangeWindowContract.numeric(p, p.name(), RangeBoundary.preceding(1), RangeBoundary.currentRow(), true))).toSQL());
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).over(p.amount().sum(),
                RangeWindowContract.temporal(p, p.id(), 1, ChronoUnit.DAYS))).toSQL());
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> {
            WindowSpec<?> frame = backend.dialect() == SqlDialect.MYSQL
                    ? MySqlDsl.of(p).nativeWindow().orderBy(p.id(), p.amount()).rangeNumericBetween(RangeBoundary.preceding(1), RangeBoundary.currentRow())
                    : PgSqlDsl.of(p).nativeWindow().orderBy(p.id(), p.amount()).rangeNumericBetween(RangeBoundary.preceding(1), RangeBoundary.currentRow());
            return ProDsl.of(p).over(p.amount().sum(), frame);
        }).toSQL());
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).over(p.amount().sum(),
                RangeWindowContract.numeric(p, p.id(), RangeBoundary.currentRow(), RangeBoundary.preceding(0), true))).toSQL());
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).over(p.amount().sum(),
                RangeWindowContract.numeric(p, p.id(), RangeBoundary.unboundedFollowing(), RangeBoundary.unboundedFollowing(), true))).toSQL());
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).over(p.amount().sum(),
                RangeWindowContract.numeric(p, p.id(), RangeBoundary.unboundedPreceding(), RangeBoundary.unboundedPreceding(), true))).toSQL());
    }

    @ParameterizedTest
    @MethodSource("backends")
    void decimalOffsetsFollowNativeKeyTyping(DecimalDslTest.Backend backend) {
        EntityQueryable<TestProductProxy, TestProduct> query = backend.client().queryable(TestProduct.class);
        Executable rendered = (org.junit.jupiter.api.function.Executable) () -> query.selectColumn(p -> ProDsl.of(p).over(p.amount().sum(),
                RangeWindowContract.numeric(p, p.id(), RangeBoundary.preceding(new BigDecimal("1.5")), RangeBoundary.currentRow(), true))).toSQL();
        if (backend.dialect() == SqlDialect.POSTGRESQL) assertThrows(IllegalArgumentException.class, rendered);
        else assertDoesNotThrow(rendered);
    }

    @Test
    void nativeFactoriesRejectWrongBackendsAndInvalidIntervals() {
        DefaultEasyEntityQuery hana = DecimalDslTest.backends().filter(b -> b.dialect() == SqlDialect.HANA).findFirst().get().client();
        assertThrows(IllegalArgumentException.class, () -> hana.queryable(TestProduct.class).selectColumn(p -> {
            ProDsl.of(p).window(PgWindowSpec::create);
            return p.id();
        }));
        assertThrows(IllegalArgumentException.class, () -> hana.queryable(TestProduct.class).selectColumn(p -> {
            ProDsl.of(p).window(MySqlWindowSpec::create);
            return p.id();
        }));
        assertEquals(RangeBoundary.Kind.FOLLOWING, RangeBoundary.following(0).kind());
        assertThrows(IllegalArgumentException.class, () -> new RangeBoundary<>(RangeBoundary.Kind.CURRENT_ROW, 1));
        assertThrows(IllegalArgumentException.class, () -> MySqlRangeInterval.duration(Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> MySqlRangeInterval.duration(Duration.ofSeconds(Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> new MySqlRangeInterval(-1, ChronoUnit.DAYS));
        assertEquals(new MySqlRangeInterval(3_600_000_000L, ChronoUnit.MICROS), MySqlRangeInterval.duration(Duration.ofHours(1)));
        DefaultEasyEntityQuery pg = backends().filter(b -> b.dialect() == SqlDialect.POSTGRESQL).findFirst().get().client();
        assertThrows(IllegalArgumentException.class, () -> pg.queryable(TestEvent.class).selectColumn(p -> PgSqlDsl.of(p).over(p.delta().sumInt(),
                PgSqlDsl.of(p).nativeWindow().orderBy(p.eventDate()).rangeIntervalBetween(
                        RangeBoundary.preceding(PgInterval.of(-1, 40, 0)), RangeBoundary.currentRow()))).toSQL());
    }
}
