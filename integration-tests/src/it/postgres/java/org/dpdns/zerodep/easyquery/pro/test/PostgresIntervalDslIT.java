package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.test.entity.PgIntervalRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class PostgresIntervalDslIT {
    private PostgresTestDatabase.Schema schema;
    private DefaultEasyEntityQuery query;

    @BeforeEach
    void createFixture() throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        source.setOptions("-c intervalstyle=sql_standard");
        source.setPrepareThreshold(-1);
        schema = PostgresTestDatabase.createSchema(source, "eqp_interval_");
        try (var statement = schema.connection().createStatement()) {
            statement.execute("CREATE TABLE " + schema.qualified("eqp_pg_interval") + " (id integer PRIMARY KEY, span interval, event_date date, occurred timestamp)");
        }
        query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
        var row = new PgIntervalRecord();
        row.setId(1);
        row.setSpan(PgInterval.of(1, 0, 0));
        row.setEventDate(LocalDate.of(2024, 1, 31));
        row.setOccurred(LocalDateTime.of(2024, 1, 31, 12, 30, 0, 123456000));
        assertEquals(1L, query.insertable(row).executeRows());
    }

    @AfterEach
    void removeFixture() throws SQLException {
        if (schema != null) schema.close();
    }

    @Test
    void nativeArithmeticAndComparisonKeepCalendarSemantics() {
        var selected = query.queryable(PgIntervalRecord.class).select(p -> {
            var pg = PgSqlDsl.of(p);
            var span = pg.view(p.span(), PgSqlTypes.interval());
            var days = pg.value(PgInterval.of(0, 30, 0), PgSqlTypes.interval());
            return Select.DRAFT.of(span.equalTo(days), span.addToDate(p.eventDate()), days.addToDate(p.eventDate()),
                    span.addToDateTime(p.occurred()), span.subtractFromDate(p.eventDate()), span.subtractFromDateTime(p.occurred()));
        });
        var row = selected.singleNotNull();
        assertTrue(row.getValue1());
        assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0), row.getValue2());
        assertEquals(LocalDateTime.of(2024, 3, 1, 0, 0), row.getValue3());
        assertEquals(LocalDateTime.of(2024, 2, 29, 12, 30, 0, 123456000), row.getValue4());
        assertEquals(LocalDateTime.of(2023, 12, 31, 0, 0), row.getValue5());
        assertEquals(LocalDateTime.of(2023, 12, 31, 12, 30, 0, 123456000), row.getValue6());
        assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        assertEquals(1L, query.queryable(PgIntervalRecord.class).where(p -> {
            var pg = PgSqlDsl.of(p);
            pg.view(p.span(), PgSqlTypes.interval()).lessThan(pg.value(PgInterval.of(2, 0, 0), PgSqlTypes.interval())).eq(true);
        }).count());
    }

    @Test
    void sqlMutationReturnsIntervalValuesAndParticipatesInRollback() {
        assertEquals(PgInterval.of(-1, 0, 0), query.queryable(PgIntervalRecord.class).select(p -> Select.DRAFT.of(
                PgSqlDsl.of(p).view(p.span(), PgSqlTypes.interval()).negated(), p.id())).singleNotNull().getValue1());
        try (var transaction = query.getEasyQueryClient().beginTransaction()) {
            assertEquals(1L, query.updatable(PgIntervalRecord.class).setColumns(p -> {
                var pg = PgSqlDsl.of(p);
                p.span().set(pg.view(p.span(), PgSqlTypes.interval()).plus(pg.value(PgInterval.of(0, 2, 3), PgSqlTypes.interval())).negated());
            }).where(p -> p.id().eq(1)).executeRows());
            assertEquals(PgInterval.of(-1, -2, -3), query.queryable(PgIntervalRecord.class).singleNotNull().getSpan());
            transaction.rollback();
        }
        assertEquals(PgInterval.of(1, 0, 0), query.queryable(PgIntervalRecord.class).singleNotNull().getSpan());
        assertEquals(PgInterval.of(0, 0, 0), query.queryable(PgIntervalRecord.class).selectColumn(p -> {
            var pg = PgSqlDsl.of(p);
            return pg.view(p.span(), PgSqlTypes.interval()).minus(pg.value(PgInterval.of(1, 0, 0), PgSqlTypes.interval()));
        }).singleNotNull());
    }

    @Test
    void derivedCteIntervalRetainsItsHandlerThroughArithmetic() {
        var inner = query.queryable(PgIntervalRecord.class).select(p -> Select.DRAFT.of(p.id(),
                PgSqlDsl.of(p).view(p.span(), PgSqlTypes.interval()).negated())).toCteAs("eqp_interval_readers");
        var outer = inner.cloneQueryable().select(p -> Select.DRAFT.of(PgSqlDsl.of(p).view(p.value2(), PgSqlTypes.interval()).negated(), p.value1()));
        assertEquals(PgInterval.of(1, 0, 0), outer.singleNotNull().getValue1());
        assertEquals(PgInterval.of(1, 0, 0), outer.cloneQueryable().singleNotNull().getValue1());
        assertEquals(PgInterval.of(-1, 0, 0), inner.singleNotNull().getValue2());
    }

    @Test
    void nullInfinityAndArithmeticErrorsRemainDistinct() {
        assertNull(query.queryable(PgIntervalRecord.class).selectColumn(p -> PgSqlDsl.of(p).value(null, PgSqlTypes.interval()).negated()).toList().get(0));
        assertSame(PgInterval.Infinity.NEGATIVE, query.queryable(PgIntervalRecord.class)
                .selectColumn(p -> PgSqlDsl.of(p).value(PgInterval.positiveInfinity(), PgSqlTypes.interval()).negated()).singleNotNull());
        assertThrows(RuntimeException.class, () -> query.queryable(PgIntervalRecord.class).selectColumn(p -> {
            var pg = PgSqlDsl.of(p);
            return pg.value(PgInterval.positiveInfinity(), PgSqlTypes.interval()).minus(pg.value(PgInterval.positiveInfinity(), PgSqlTypes.interval()));
        }).toList());
        assertThrows(RuntimeException.class, () -> query.queryable(PgIntervalRecord.class).selectColumn(p ->
                PgSqlDsl.of(p).value(PgInterval.of(0, 0, Long.MIN_VALUE), PgSqlTypes.interval()).negated()).toList());
    }
}
