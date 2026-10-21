package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.MySqlDurationRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationType;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;

import static org.junit.jupiter.api.Assertions.*;

class MySqlDurationTypeTest {
    @Test
    void precisionAndCapacityAreCheckedWithoutTakingAnOverflowingAbsoluteValue() {
        MySqlDurationType micros = MySqlTypes.durationTime(6);
        assertEquals(Duration.ofSeconds(3_020_399), micros.prepareValue(Duration.ofSeconds(3_020_399)));
        assertEquals(Duration.ofSeconds(-3_020_399), micros.prepareValue(Duration.ofSeconds(-3_020_399)));
        assertEquals(Duration.ofNanos(-1000), micros.prepareValue(Duration.ofNanos(-1000)));
        assertNull(micros.prepareValue(null));
        assertThrows(IllegalArgumentException.class, () -> micros.prepareValue(Duration.ofSeconds(3_020_399).plusNanos(1000)));
        assertThrows(IllegalArgumentException.class, () -> micros.prepareValue(Duration.ofSeconds(Long.MIN_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> micros.prepareValue(Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.durationTime(3).prepareValue(Duration.ofNanos(-1000)));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.durationTime(7));
    }

    @Test
    void viewPrecisionAndClockMappingStayExplicit() {
        DefaultEasyEntityQuery query = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        JdbcTypeHandlerManager handlers = query.getRuntimeContext().getJdbcTypeHandlerManager();
        JdbcTypeHandler clock = handlers.getHandler(LocalTime.class);
        MySqlExtensions.configureTypeHandlers(handlers);
        assertSame(clock, handlers.getHandler(LocalTime.class));
        assertThrows(IllegalArgumentException.class, () -> query.queryable(MySqlDurationRecord.class).selectColumn(p ->
                MySqlDsl.of(p).view(p.clock(), MySqlTypes.durationTime(0))));
        assertThrows(IllegalArgumentException.class, () -> query.queryable(MySqlDurationRecord.class).selectColumn(p ->
                MySqlDsl.of(p).view(p.elapsed(), MySqlTypes.durationTime(3))));
        assertThrows(IllegalArgumentException.class, () -> query.queryable(MySqlDurationRecord.class).selectColumn(p ->
                MySqlDsl.of(p).view(p.started(), MySqlTypes.durationTime(6))));
        assertDoesNotThrow(() -> query.queryable(MySqlDurationRecord.class).selectColumn(p ->
                MySqlDsl.of(p).nativeCast(p.elapsed(), MySqlTypes.durationTime(3))).toSQL());
    }
}
