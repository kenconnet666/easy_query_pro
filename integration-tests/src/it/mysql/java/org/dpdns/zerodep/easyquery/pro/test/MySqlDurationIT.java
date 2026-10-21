package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.enums.SQLExecuteStrategyEnum;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.MySqlDurationRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.ArrayList;
import com.mysql.cj.jdbc.MysqlDataSource;
import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft6Proxy;
import com.easy.query.core.proxy.core.draft.Draft6;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationType;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationExpression;

import static org.junit.jupiter.api.Assertions.*;

class MySqlDurationIT {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void elapsedTimeUsesDurationAndPreservesMicroseconds(boolean serverPrepared, boolean pooled) throws SQLException {
        MysqlDataSource source = MySqlTestDatabase.dataSource();
        source.setUseServerPrepStmts(serverPrepared);
        source.setRewriteBatchedStatements(true);
        source.setSessionVariables("sql_mode='STRICT_ALL_TABLES',div_precision_increment=0");
        HikariConfig config = new HikariConfig();
        config.setDataSource(source);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        try (HikariDataSource pool = pooled ? new HikariDataSource(config) : null) {
            DataSource dataSource = pool == null ? source : pool;
            try (MySqlTestDatabase.Table fixture = MySqlTestDatabase.createTable(dataSource, "eqp_duration_", "id int PRIMARY KEY, elapsed "
                    + MySqlTypes.durationTime(6).ddlType(SqlDialect.MYSQL) + ", clock TIME, started DATETIME(6)")) {
                String table = fixture.name();
                DefaultEasyEntityQuery query = TestClients.configured(dataSource, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
                MySqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
                List<Duration> values = Arrays.asList(Duration.ZERO, Duration.ofNanos(-1000), Duration.ofHours(27).plusNanos(123456000),
                        Duration.ofHours(27).plusNanos(123456000).negated(), Duration.ofSeconds(3_020_399), Duration.ofSeconds(-3_020_399), null);
                ArrayList<MySqlDurationRecord> input = new ArrayList<MySqlDurationRecord>();
                for (int i = 0; i < values.size(); i++) {
                    MySqlDurationRecord row = new MySqlDurationRecord();
                    row.setId(i);
                    row.setElapsed(values.get(i));
                    row.setClock(LocalTime.of(12, 34, 56));
                    row.setStarted(LocalDateTime.of(2024, 1, 31, 0, 0, 0, 123456000));
                    input.add(row);
                }
                assertEquals(values.size(), query.insertable(input).asTable(table).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows());
                List<MySqlDurationRecord> rows = query.queryable(MySqlDurationRecord.class).asTable(table).orderBy(p -> p.id().asc()).toList();
                assertEquals(values, rows.stream().map(MySqlDurationRecord::getElapsed).collect(Collectors.toList()));
                assertTrue(rows.stream().allMatch(row -> row.getClock().equals(LocalTime.of(12, 34, 56))));
                List<BigDecimal> seconds = query.queryable(MySqlDurationRecord.class).asTable(table).orderBy(p -> p.id().asc()).selectColumn(p ->
                        MySqlDsl.of(p).view(p.elapsed(), MySqlTypes.durationTime(6)).toSeconds()).toList();
                for (int i = 0; i < values.size(); i++) {
                    Duration value = values.get(i);
                    if (value == null) assertNull(seconds.get(i));
                    else
                        assertEquals(0, BigDecimal.valueOf(value.getSeconds()).add(BigDecimal.valueOf(value.getNano(), 9)).compareTo(seconds.get(i)), value.toString());
                }
                BigDecimal total = query.queryable(MySqlDurationRecord.class).asTable(table).selectColumn(p ->
                        MySqlDsl.of(p).view(p.elapsed(), MySqlTypes.durationTime(6)).toSeconds().sum(BigDecimal.class)).singleNotNull();
                assertEquals(0, new BigDecimal("-0.000001").compareTo(total));
                verifyExpressions(query, table);
                try (Transaction transaction = query.getEasyQueryClient().beginTransaction()) {
                    assertEquals(1L, query.updatable(MySqlDurationRecord.class).asTable(table).setColumns(p ->
                            p.elapsed().set(MySqlDsl.of(p).view(p.elapsed(), MySqlTypes.durationTime(6)).negated())).where(p -> p.id().eq(2)).executeRows());
                    assertEquals(values.get(3), query.queryable(MySqlDurationRecord.class).asTable(table).where(p -> p.id().eq(2)).singleNotNull().getElapsed());
                    transaction.rollback();
                }
                assertEquals(values.get(2), query.queryable(MySqlDurationRecord.class).asTable(table).where(p -> p.id().eq(2)).singleNotNull().getElapsed());
            }
        }
    }

    private static void verifyExpressions(DefaultEasyEntityQuery query, String table) {
        EntityQueryable<Draft6Proxy<Duration, Duration, Duration, LocalDateTime, LocalDateTime, Boolean>, Draft6<Duration, Duration, Duration, LocalDateTime, LocalDateTime, Boolean>> selected = query.queryable(MySqlDurationRecord.class).asTable(table).where(p -> p.id().eq(2)).select(p -> {
            MySqlDsl mysql = MySqlDsl.of(p);
            MySqlDurationType type = MySqlTypes.durationTime(6);
            MySqlDurationExpression<Duration> value = mysql.view(p.elapsed(), type);
            MySqlDurationExpression<Duration> hour = mysql.value(Duration.ofHours(1), type);
            return Select.DRAFT.of(value.plus(hour), value.minus(hour), value.negated(), value.addToDateTime(p.started()),
                    value.subtractFromDateTime(p.started()), value.lessThan(hour));
        });
        Draft6<Duration, Duration, Duration, LocalDateTime, LocalDateTime, Boolean> row = selected.singleNotNull();
        assertEquals(Duration.ofHours(28).plusNanos(123456000), row.getValue1());
        assertEquals(Duration.ofHours(26).plusNanos(123456000), row.getValue2());
        assertEquals(Duration.ofHours(27).plusNanos(123456000).negated(), row.getValue3());
        assertEquals(LocalDateTime.of(2024, 2, 1, 3, 0, 0, 246912000), row.getValue4());
        assertEquals(LocalDateTime.of(2024, 1, 29, 21, 0), row.getValue5());
        assertFalse(row.getValue6());
        assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        assertEquals(Duration.ofHours(12).plusMinutes(34).plusSeconds(56), query.queryable(MySqlDurationRecord.class).asTable(table).where(p -> p.id().eq(0))
                .selectColumn(p -> MySqlDsl.of(p).nativeCast(p.clock(), MySqlTypes.durationTime(6))).singleNotNull());
        assertEquals(Duration.ofSeconds(3_020_399), query.queryable(MySqlDurationRecord.class).asTable(table).where(p -> p.id().eq(4))
                .selectColumn(p -> MySqlDsl.of(p).view(p.elapsed(), MySqlTypes.durationTime(6))
                        .plus(MySqlDsl.of(p).value(Duration.ofSeconds(1), MySqlTypes.durationTime(6)))).singleNotNull());
        assertNull(query.queryable(MySqlDurationRecord.class).asTable(table).where(p -> p.id().eq(0))
                .selectColumn(p -> MySqlDsl.of(p).value(null, MySqlTypes.durationTime(6)).negated()).toList().get(0));
    }
}
