package org.dpdns.zerodep.easyquery.pro.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.dpdns.zerodep.easyquery.pro.test.entity.TemporalRecord;
import com.easy.query.core.basic.api.database.CodeFirstCommandTxArg;
import com.easy.query.core.basic.jdbc.executor.ExecutorContext;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.impl.memory.row.DefaultMemoryResultSetRow;
import com.easy.query.core.enums.ContextTypeEnum;
import com.easy.query.core.enums.ExecuteMethodEnum;
import com.easy.query.core.enums.conn.ConnectionStrategyEnum;
import com.easy.query.core.expression.sql.builder.EasyExpressionContext;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("java.util.TimeZone.default")
class HanaTemporalBindingIT {
    @Test
    void localDateTimeInADaylightSavingGapIsNotShiftedByTheJvmZone() throws SQLException {
        var previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            var value = LocalDateTime.of(2024, 3, 31, 2, 30, 11, 123_456_700);
            String sql = "SELECT CAST(? AS TIMESTAMP) FROM DUMMY";
            try (var connection = HanaTestDatabase.connect(); var statement = connection.prepareStatement(sql)) {
                var calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
                statement.setTimestamp(1, Timestamp.from(value.toInstant(ZoneOffset.UTC)), calendar);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(value, LocalDateTime.ofInstant(rows.getTimestamp(1, calendar).toInstant(), ZoneOffset.UTC));
                }
            }
            var client = HanaTestDatabase.client().getEasyQueryClient();
            assertEquals(value, client.sqlQuery(sql, LocalDateTime.class, List.of(value)).get(0));
            assertEquals(value, client.sqlQuery("SELECT TO_TIMESTAMP('2024-03-31 02:30:11.1234567') FROM DUMMY", LocalDateTime.class).get(0));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void localDateKeepsACalendarDayThatTheJvmZoneSkipped() throws SQLException {
        var previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Apia"));
            var date = LocalDate.of(2011, 12, 30);
            var client = HanaTestDatabase.client().getEasyQueryClient();
            assertEquals(date, client.sqlQuery("SELECT CAST(? AS DATE) FROM DUMMY", LocalDate.class, List.of(date)).get(0));
            assertEquals(date, client.sqlQuery("SELECT TO_DATE('2011-12-30') FROM DUMMY", LocalDate.class).get(0));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void instantUsesUtcAndRejectsUnrepresentablePrecision() throws SQLException {
        var previous = TimeZone.getDefault();
        try {
            var value = Instant.parse("2024-01-02T03:04:05.123456700Z");
            for (String zone : List.of("UTC", "Asia/Shanghai", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var client = HanaTestDatabase.client().getEasyQueryClient();
                assertEquals(value, client.sqlQuery("SELECT CAST(? AS TIMESTAMP) FROM DUMMY", Instant.class, List.of(value)).get(0));
                assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123_456_700),
                        client.sqlQuery("SELECT CAST(? AS TIMESTAMP) FROM DUMMY", LocalDateTime.class, List.of(value)).get(0));
            }
            var client = HanaTestDatabase.client().getEasyQueryClient();
            assertThrows(RuntimeException.class, () -> client.sqlQuery("SELECT CAST(? AS TIMESTAMP) FROM DUMMY", Instant.class, List.of(value.plusNanos(1))));
            assertThrows(RuntimeException.class, () -> client.sqlQuery("SELECT CAST(? AS TIMESTAMP) FROM DUMMY", LocalDateTime.class, List.of(LocalDateTime.of(2024, 1, 1, 0, 0, 0, 1))));
            assertThrows(RuntimeException.class, () -> client.sqlQuery("SELECT CAST(? AS TIME) FROM DUMMY", LocalTime.class, List.of(LocalTime.of(12, 34, 56, 1))));
            assertNull(client.sqlQuery("SELECT CAST(? AS TIMESTAMP) FROM DUMMY", Instant.class, Arrays.asList((Object) null)).get(0));
            assertEquals(LocalTime.of(12, 34, 56), client.sqlQuery("SELECT CAST(? AS TIME) FROM DUMMY", LocalTime.class, List.of(LocalTime.of(12, 34, 56))).get(0));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void entityBatchBindingProjectionAndUpdatesShareTheSameTemporalContract() throws SQLException {
        var previous = TimeZone.getDefault();
        try (var schema = HanaTestDatabase.createSchema("TEMPORAL")) {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            var query = HanaTestDatabase.client();
            var client = query.getEasyQueryClient();
            var data = client.getRuntimeContext().getMigrationsSQLGenerator().parseEntity(TemporalRecord.class);
            data.setSchema(schema.name());
            client.getDatabaseCodeFirst().createTableCommandByMigrationData(List.of(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            var first = new TemporalRecord();
            first.setId(1);
            first.setOccurred(Instant.parse("2024-03-31T01:30:00.123456700Z"));
            first.setLocal(LocalDateTime.of(2024, 3, 31, 2, 30, 0, 123_456_700));
            first.setDay(LocalDate.of(2011, 12, 30));
            first.setClock(LocalTime.of(12, 34, 56));
            var empty = new TemporalRecord();
            empty.setId(2);
            assertEquals(2L, query.insertable(List.of(first, empty)).asSchema(s -> schema.name()).batch(true).executeRows());
            var rows = query.queryable(TemporalRecord.class).asSchema(s -> schema.name()).orderBy(t -> t.id().asc()).toList();
            assertEquals(first.getOccurred(), rows.get(0).getOccurred());
            assertEquals(first.getLocal(), rows.get(0).getLocal());
            assertEquals(first.getDay(), rows.get(0).getDay());
            assertEquals(first.getClock(), rows.get(0).getClock());
            assertNull(rows.get(1).getOccurred());
            assertNull(rows.get(1).getLocal());
            assertEquals(List.of(first.getOccurred()), query.queryable(TemporalRecord.class).asSchema(s -> schema.name())
                    .where(t -> t.occurred().eq(first.getOccurred())).selectColumn(t -> t.occurred()).toList());
            first.setOccurred(first.getOccurred().plusSeconds(7));
            assertEquals(1L, query.updatable(first).asSchema(s -> schema.name()).executeRows());
            assertEquals(first.getOccurred(), query.queryable(TemporalRecord.class).asSchema(s -> schema.name())
                    .where(t -> t.id().eq(1)).selectColumn(t -> t.occurred()).toList().get(0));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void dateLabelsArePreservedAndTheHanaCalendarGapIsRejected() throws SQLException {
        var client = HanaTestDatabase.client().getEasyQueryClient();
        for (var date : List.of(LocalDate.of(1, 1, 1), LocalDate.of(1500, 2, 28), LocalDate.of(1582, 10, 4), LocalDate.of(1582, 10, 15), LocalDate.of(9999, 12, 31))) {
            assertAll(date.toString(),
                    () -> assertEquals(date.toString(), client.sqlQuery("SELECT TO_NVARCHAR(CAST(? AS DATE),'YYYY-MM-DD') FROM DUMMY", String.class, List.of(date)).get(0), "binding"),
                    () -> assertEquals(date, client.sqlQuery("SELECT TO_DATE(?) FROM DUMMY", LocalDate.class, List.of(date.toString())).get(0), "reading"));
        }
        assertThrows(RuntimeException.class, () -> client.sqlQuery("SELECT CAST(? AS DATE) FROM DUMMY", LocalDate.class, List.of(LocalDate.of(0, 1, 1))));
        assertThrows(RuntimeException.class, () -> client.sqlQuery("SELECT CAST(? AS DATE) FROM DUMMY", LocalDate.class, List.of(LocalDate.of(1582, 10, 10))));
        assertThrows(RuntimeException.class, () -> client.sqlQuery("SELECT TO_DATE('1500-02-29') FROM DUMMY", LocalDate.class));
    }

    @Test
    void legacyMapResultsRejectLossAndUpstreamMemoryRowsPreserveLogicalValues() throws SQLException {
        var previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            var client = HanaTestDatabase.client().getEasyQueryClient();
            var runtime = client.getRuntimeContext();
            String sql = "SELECT TO_TIMESTAMP('2024-03-31 02:30:00.1234567') AS \"local\",TO_DATE('0001-01-01') AS \"day\",TO_TIME('12:34:56') AS \"clock\" FROM DUMMY";
            var local = LocalDateTime.of(2024, 3, 31, 2, 30, 0, 123_456_700);
            // 上游 Map 保持 java.sql 类型；Timestamp 无法表示 JVM 时区的 DST 缺口，必须拒绝静默归一化。
            assertThrows(RuntimeException.class, () -> client.sqlQueryMap(sql));
            var map = client.sqlQueryMap("SELECT TO_TIMESTAMP('2024-03-31 04:30:00') AS \"local\" FROM DUMMY").get(0);
            assertEquals(LocalDateTime.of(2024, 3, 31, 4, 30), assertInstanceOf(Timestamp.class, map.get("local")).toLocalDateTime());
            var context = ExecutorContext.create(new EasyExpressionContext(runtime, ContextTypeEnum.QUERY), true, ExecuteMethodEnum.LIST);
            var manager = runtime.getConnectionManager();
            var connection = manager.getEasyConnections(1, runtime.getDataSourceManager().getDefaultDataSourceName(), ConnectionStrategyEnum.ShareConnection).get(0);
            try (var result = runtime.getJdbcSQLExecutor().query(context, connection, sql, List.of(), true, false)) {
                assertTrue(result.next());
                var row = new DefaultMemoryResultSetRow(result, 3);
                assertEquals(local, row.getValue(1));
                assertEquals(LocalDate.of(1, 1, 1), row.getValue(2));
                assertEquals(LocalTime.of(12, 34, 56), row.getValue(3));
            } finally {
                manager.closeEasyConnection(connection);
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
