package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.core.func.def.enums.DateTimeDurationEnum;
import com.easy.query.core.func.def.enums.DateTimeUnitEnum;
import com.easy.query.core.func.def.enums.TimeUnitEnum;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.impl.DateTimeTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestEventProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HanaDateTimeFunctionsIT {
    private DefaultEasyEntityQuery query;

    @BeforeEach
    void createClient() throws SQLException {
        query = new DefaultEasyEntityQuery(EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(HanaTestDatabase.dataSource())
                .useDatabaseConfigure(new HanaDatabaseConfiguration()).build());
    }

    @Test
    void calendarArithmeticHandlesLeapYearsMonthEndsColumnsAndSubseconds() {
        var timestamp = LocalDateTime.of(2024, 1, 31, 12, 34, 56, 123_456_700);
        assertEquals(timestamp.plusMonths(1), source(timestamp, 1).selectColumn(e -> e.occurred().plusMonths(1)).toList().get(0));
        assertEquals(timestamp.plusMonths(-1), source(timestamp, -1).selectColumn(e -> e.occurred().plusMonths(e.delta())).toList().get(0));
        assertEquals(timestamp.plusDays(2), source(timestamp, 2).selectColumn(e -> e.occurred().plusDays(e.delta())).toList().get(0));
        assertEquals(timestamp.plusHours(-3), source(timestamp, 0).selectColumn(e -> e.occurred().plusHours(-3)).toList().get(0));
        assertEquals(timestamp.plusNanos(1_000_000), source(timestamp, 1).selectColumn(e -> e.occurred().plus(e.delta(), TimeUnitEnum.MILLISECONDS)).toList().get(0));
        var leapDay = timestamp.withMonth(2).withDayOfMonth(29);
        assertEquals(leapDay.plusYears(1), source(leapDay, 0).selectColumn(e -> e.occurred().plusYears(1)).toList().get(0));
        assertEquals(timestamp.toLocalDate().plusMonths(1), source(timestamp, 0).selectColumn(e -> e.eventDate().plusMonths(1)).toList().get(0));
    }

    @Test
    void componentsMatchTheExistingSundayAndMondayNumberingContracts() {
        var sunday = LocalDateTime.of(2024, 3, 3, 15, 4, 5, 900_000_000);
        var expected = List.of(2024, 3, 3, 15, 4, 5, 63, 0, 7);
        var units = List.of(DateTimeUnitEnum.Year, DateTimeUnitEnum.Month, DateTimeUnitEnum.Day,
                DateTimeUnitEnum.Hour, DateTimeUnitEnum.Minute, DateTimeUnitEnum.Second,
                DateTimeUnitEnum.DayOfYear, DateTimeUnitEnum.DayOfWeek, DateTimeUnitEnum.DayOfWeekSunDayLastDay);
        for (int i = 0; i < units.size(); i++) {
            var unit = units.get(i);
            assertEquals(expected.get(i), source(sunday, 0).selectColumn(e -> e.occurred().dateTimeProp(unit)).toList().get(0), unit.name());
        }
    }

    @Test
    @SuppressWarnings("deprecation") // 旧 duration 的方向仍是公开兼容契约，不能仅验证新的 duration2。
    void elapsedUnitsTruncateTowardZeroAndKeepBothDurationDirections() {
        var before = LocalDateTime.of(2024, 1, 1, 0, 0);
        var after = before.plusHours(47).plusMinutes(30).plusNanos(500_000_000);
        assertEquals(1L, source(before, 0).selectColumn(e -> e.occurred().duration(after).toDays()).toList().get(0));
        assertEquals(-47L, source(after, 0).selectColumn(e -> e.occurred().duration(before).toHours()).toList().get(0));
        assertEquals(0L, source(before.plusNanos(500_000_000), 0).selectColumn(e -> e.occurred().duration(before).toSeconds()).toList().get(0));
        assertEquals(2850L, source(before, 0).selectColumn(e -> e.occurred().duration(after).toMinutes()).toList().get(0));
        assertEquals(171000L, source(before, 0).selectColumn(e -> e.occurred().duration(after).toSeconds()).toList().get(0));
        assertEquals(-1L, source(before, 0).selectColumn(e -> new NumberTypeExpressionImpl<Long>(
                e.getEntitySQLContext(), e.getTable(), null, fx -> fx.duration(args -> {
                    PropTypeColumn.columnFuncSelector(args, e.occurred());
                    args.value(after);
                }, DateTimeDurationEnum.Days), Long.class)).toList().get(0));
    }

    @Test
    void datePatternsBindTextAndSupportQuotedLiteralsAndFractionalPrecision() {
        var value = LocalDateTime.of(2024, 3, 3, 15, 4, 5, 123_456_700);
        for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy年M月d日 H:m:s", "yyMMddHHmmss",
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS", "h:mm a", "SSSSSSSSS", "'O''Reilly 中文'", "''", "")) {
            var selection = source(value, 0).selectColumn(e -> e.occurred().format(pattern));
            assertEquals(value.format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT)), selection.toList().get(0), pattern);
            var first = selection.toSQLResult();
            var repeated = selection.cloneQueryable().toSQLResult();
            assertFalse(first.getSQL().contains("Reilly"));
            assertEquals(first.getSQL(), repeated.getSQL());
            assertEquals(first.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList(),
                    repeated.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        }
        assertNull(source(null, 0).selectColumn(e -> e.occurred().format("yyyy-MM-dd")).toList().get(0));
        assertNull(source(null, 0).selectColumn(e -> e.occurred().format("'literal'")).toList().get(0));
        assertThrows(IllegalArgumentException.class, () -> source(value, 0).selectColumn(e -> e.occurred().format("MMM")).toSQL());
        assertThrows(IllegalArgumentException.class, () -> source(value, 0).selectColumn(e -> e.occurred().format("yyyy-'open")).toSQL());
    }

    @Test
    void coreTimeUnitAdditionPreservesNativePrecisionAndRejectsOverflow() {
        var value = LocalDateTime.of(2024, 1, 1, 0, 0);
        assertEquals(value.plusNanos(100), source(value, 0).selectColumn(e -> new DateTimeTypeExpressionImpl<LocalDateTime>(
                e.getEntitySQLContext(), e.getTable(), null, fx -> fx.plusDateTime(args ->
                PropTypeColumn.columnFuncSelector(args, e.occurred()), 100, TimeUnit.NANOSECONDS), LocalDateTime.class)).toList().get(0));
        var functions = query.getRuntimeContext().fx();
        assertThrows(IllegalArgumentException.class, () -> functions.plusDateTime(args -> args.value(value), 1, TimeUnit.NANOSECONDS));
        assertThrows(ArithmeticException.class, () -> functions.plusDateTime(args -> args.value(value), Long.MAX_VALUE, TimeUnit.HOURS));
    }

    private EntityQueryable<TestEventProxy, TestEvent> source(LocalDateTime timestamp, int delta) {
        return query.queryable("SELECT CAST(? AS TIMESTAMP) AS \"occurred\",CAST(? AS DATE) AS \"event_date\","
                + "CAST(? AS INTEGER) AS \"delta\" FROM DUMMY", TestEvent.class,
                Arrays.asList(timestamp, timestamp == null ? null : timestamp.toLocalDate(), delta));
    }
}
