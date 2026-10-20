package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.basic.jdbc.types.handler.LocalDateTimeTypeHandler;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.time.Instant;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("java.util.TimeZone.default")
class PostgresTemporalBindingIT {
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void timestampWithoutTimeZonePreservesFieldsInsideAJvmDstGap(int prepareThreshold) {
        var previousZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            var source = PostgresTestDatabase.dataSource();
            source.setPrepareThreshold(prepareThreshold);
            source.setOptions("-c TimeZone=UTC");
            var query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
            PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
            var value = LocalDateTime.of(2024, 3, 10, 2, 30, 0, 123456000);
            // 先读取数据库端文本，独立证明绑定没有把 02:30 规范化为 JVM 中实际存在的 03:30。
            var bound = query.queryable("SELECT 1 AS id,to_char(CAST(? AS TIMESTAMP),'YYYY-MM-DD HH24:MI:SS.US') AS name,"
                    + "CAST(0 AS NUMERIC) AS amount", TestProduct.class, List.of(value)).singleNotNull().getName();
            var read = query.queryable("SELECT TIMESTAMP '2024-03-10 02:30:00.123456' AS occurred,"
                    + "CAST(NULL AS DATE) AS event_date,1 AS delta", TestEvent.class).singleNotNull().getOccurred();
            assertAll(() -> assertEquals("2024-03-10 02:30:00.123456", bound),
                    () -> assertEquals(value, read));
            var overlap = LocalDateTime.of(2024, 11, 3, 1, 30, 0, 123456000);
            assertEquals(overlap, query.getEasyQueryClient().sqlQuery("SELECT CAST(? AS TIMESTAMP)", LocalDateTime.class, List.of(overlap)).get(0));
            assertNull(query.getEasyQueryClient().sqlQuery("SELECT CAST(NULL AS TIMESTAMP)", LocalDateTime.class).get(0));
        } finally {
            TimeZone.setDefault(previousZone);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void explicitHandlerAnnotationsAndTimestampWithTimeZoneKeepTheirContracts(int prepareThreshold) {
        var previousZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            var source = PostgresTestDatabase.dataSource();
            source.setPrepareThreshold(prepareThreshold);
            var client = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure).getEasyQueryClient();
            var value = LocalDateTime.of(2024, 3, 10, 2, 30, 0, 123456000);
            assertEquals(value, client.sqlQuery("SELECT CAST(? AS TIMESTAMP) AS occurred", ExplicitRow.class, List.of(value)).get(0).getOccurred());
            var instant = Instant.parse("2024-03-10T07:30:00.123456Z");
            var timestamp = client.sqlQuery("SELECT TIMESTAMPTZ '2024-03-10 07:30:00.123456+00'", Timestamp.class).get(0);
            assertEquals(instant, timestamp.toInstant());
            var id = UUID.fromString("f1a4c9ab-f81f-4629-810f-b48c21022772");
            assertEquals(id, client.sqlQuery("SELECT CAST(? AS UUID)", UUID.class, List.of(id)).get(0));
        } finally {
            TimeZone.setDefault(previousZone);
        }
    }

    @Test
    void customPropertyHandlerIsNotReplacedByTheDefaultAlias() {
        var client = TestClients.configured(PostgresTestDatabase.dataSource(), new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure).getEasyQueryClient();
        client.getRuntimeContext().getJdbcTypeHandlerManager().appendHandler(LocalDateTime.class, new CustomDateTime(), true);
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0),
                client.sqlQuery("SELECT TIMESTAMP '2024-03-10 02:30:00.123456' AS occurred", CustomRow.class).get(0).getOccurred());
    }

    @Test
    void unshardedCustomHandlerRetainsTheDriversUntypedTimestamp() {
        var client = TestClients.configured(PostgresTestDatabase.dataSource(), new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure).getEasyQueryClient();
        client.getRuntimeContext().getJdbcTypeHandlerManager().appendHandler(LocalDateTime.class, new RawTimestampReader(), true);
        assertEquals(LocalDateTime.of(2024, 1, 2, 12, 30),
                client.sqlQuery("SELECT TIMESTAMP '2024-01-02 12:30:00' AS occurred", RawTimestampRow.class).get(0).getOccurred());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shardGroupingAndOrderingKeepLocalFieldsBeforeMemoryMerge(int prepareThreshold) throws SQLException {
        var previousZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            var source = PostgresTestDatabase.dataSource();
            source.setPrepareThreshold(prepareThreshold);
            try (var schema = PostgresTestDatabase.createSchema(source, "eqp_pg_temporal_");
                 var ddl = schema.connection().createStatement()) {
                var tables = List.of("temporal_first", "temporal_second");
                for (String table : tables)
                    ddl.execute("CREATE TABLE " + schema.qualified(table)
                            + " (id INTEGER PRIMARY KEY,name VARCHAR(200),amount DECIMAL(19,4),created TIMESTAMP(6))");
                ShardingDslContract.verify(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure, tables, query -> {
                    var value = LocalDateTime.of(2024, 3, 10, 2, 30, 0, 123456000);
                    assertEquals(2L, query.updatable(ShardedProduct.class).setColumns(p -> p.created().set(value)).where(p -> p.id().gt(0)).executeRows());
                    var ordered = query.queryable(ShardedProduct.class).orderBy(p -> {
                        p.created().asc();
                        p.id().asc();
                    }).toList();
                    assertEquals(List.of(value, value), ordered.stream().map(ShardedProduct::getCreated).toList());
                    // 分组结果会缓存无类型 getObject 的值，必须在进入 merger 前已经保存 LocalDateTime。
                    var grouped = query.queryable(ShardedProduct.class).groupBy(p -> GroupKeys.of(p.created()))
                            .select(g -> Select.DRAFT.of(g.key1(), g.count())).singleNotNull();
                    assertEquals(value, grouped.getValue1());
                    assertEquals(2L, grouped.getValue2());
                    assertEquals(List.of(value), query.queryable(ShardedProduct.class).selectColumn(p -> p.created()).distinct().toList());
                });
            }
        } finally {
            TimeZone.setDefault(previousZone);
        }
    }

    public static class ExplicitRow {
        @Column(typeHandler = LocalDateTimeTypeHandler.class)
        private LocalDateTime occurred;

        public LocalDateTime getOccurred() {
            return occurred;
        }

        public void setOccurred(LocalDateTime occurred) {
            this.occurred = occurred;
        }
    }

    public static class CustomRow {
        @Column(typeHandler = CustomDateTime.class)
        private LocalDateTime occurred;

        public LocalDateTime getOccurred() {
            return occurred;
        }

        public void setOccurred(LocalDateTime occurred) {
            this.occurred = occurred;
        }
    }

    public static class CustomDateTime extends LocalDateTimeTypeHandler {
        @Override
        public Object getValue(JdbcProperty property, StreamResultSet result) {
            return LocalDateTime.of(2000, 1, 1, 0, 0);
        }
    }

    public static class RawTimestampRow {
        @Column(typeHandler = RawTimestampReader.class)
        private LocalDateTime occurred;

        public LocalDateTime getOccurred() {
            return occurred;
        }

        public void setOccurred(LocalDateTime occurred) {
            this.occurred = occurred;
        }
    }

    public static class RawTimestampReader extends LocalDateTimeTypeHandler {
        @Override
        public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
            var timestamp = (Timestamp) result.getObject(property.getJdbcIndex());
            return timestamp == null ? null : timestamp.toLocalDateTime();
        }
    }
}
