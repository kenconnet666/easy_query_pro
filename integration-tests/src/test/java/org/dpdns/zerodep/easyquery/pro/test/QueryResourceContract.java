package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.basic.extension.conversion.ColumnReader;
import com.easy.query.core.basic.extension.listener.JdbcExecutorListener;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.proxy.core.draft.Draft3;
import com.easy.query.core.proxy.core.draft.proxy.Draft3Proxy;
import com.easy.query.core.proxy.sql.Select;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;
import java.sql.PreparedStatement;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import com.easy.query.core.basic.jdbc.executor.internal.enumerable.JdbcStreamResult;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定结果与资源上界作为门槛，耗时只记录可复现样本，不作跨机器性能断言。
 */
final class QueryResourceContract {
    static final int ROWS = 2_049;
    private static final int FETCH = 64;
    private static final int WORKERS = 4;
    private static final int ROUNDS = 6;

    private QueryResourceContract() {
    }

    static void seed(Connection owner, String table) throws SQLException {
        try (PreparedStatement insert = owner.prepareStatement("INSERT INTO " + table + " VALUES (?,?,?)")) {
            for (int id = 1; id <= ROWS; id++) {
                insert.setInt(1, id);
                if (name(id) == null) insert.setNull(2, Types.VARCHAR);
                else insert.setString(2, name(id));
                if (amount(id) == null) insert.setNull(3, Types.DECIMAL);
                else insert.setBigDecimal(3, amount(id));
                insert.addBatch();
            }
            assertEquals(ROWS, insert.executeBatch().length);
        }
    }

    static void verify(DataSource source, DatabaseConfiguration database, Consumer<ServiceCollection> extensions,
                       String table, String schema) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setDataSource(source);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            JdbcResourceProbe resources = new JdbcResourceProbe(pool);
            EasyQueryClient client = EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(resources.dataSource())
                    .useDatabaseConfigure(database).customConfigure(extensions)
                    .replaceService(JdbcExecutorListener.class, resources).build();
            DefaultEasyEntityQuery query = new DefaultEasyEntityQuery(client);
            Supplier<EntityQueryable<TestProductProxy, TestProduct>> rows = () -> {
                EntityQueryable<TestProductProxy, TestProduct> current = query.queryable(TestProduct.class).asTable(ignored -> table);
                return schema == null ? current : current.asSchema(ignored -> schema);
            };
            try {
                assertEquals(ROWS, rows.get().count());
                long started = System.nanoTime();
                int before = resources.sqlExecutions();
                int beforeJdbc = resources.jdbcExecutions();
                int beforeStatements = resources.statementCount();
                int beforeResults = resources.resultCount();
                verifyStreams(query, rows, pool, resources);
                verifyConcurrentReaders(rows, pool);
                reusable(rows, pool, resources);
                assertEquals(34, resources.sqlExecutions() - before, "Four stream runs, five reuse checks and twenty-five concurrent reads");
                assertEquals(resources.sqlExecutions(), resources.jdbcExecutions(), "Each business query should execute one JDBC statement");
                System.out.printf("RESOURCE_BASELINE dialect=%s rows=%d checksum=%d fetch=%d workers=%d pool=%d ormSql=%d jdbcExecutions=%d statements=%d resultSets=%d elapsedMs=%d%n",
                        query.getRuntimeContext().getService(SqlDialect.class), ROWS, checksum(ROWS), FETCH, WORKERS, 2,
                        resources.sqlExecutions() - before, resources.jdbcExecutions() - beforeJdbc,
                        resources.statementCount() - beforeStatements, resources.resultCount() - beforeResults,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                QueryBenchmarkContract.verify(pool, database, extensions, table, schema);
            } finally {
                client.getRuntimeContext().getEasyTimeJobManager().shutdown();
                client.getRuntimeContext().getShardingExecutorService().getExecutorService().shutdown();
            }
        }
    }

    private static void verifyStreams(DefaultEasyEntityQuery query, Supplier<EntityQueryable<TestProductProxy, TestProduct>> rows,
                                      HikariDataSource pool, JdbcResourceProbe resources) throws SQLException {
        for (int scenario = 0; scenario < 4; scenario++) {
            int beforeSql = resources.sqlExecutions();
            int beforeJdbc = resources.jdbcExecutions();
            try (Transaction transaction = query.getEasyQueryClient().beginTransaction()) {
                if (scenario == 0) {
                    QueryResourceContract.ReadSummary summary = read(rows.get().orderBy(p -> p.id().asc()).getClientQueryable(), TestProduct::getId,
                            row -> assertRow(row.getId(), row.getName(), row.getAmount(), ""), Integer.MAX_VALUE);
                    assertEquals(new ReadSummary(ROWS, checksum(ROWS)), summary);
                } else {
                    QueryResourceContract.ReaderProbe reader = new ReaderProbe("", scenario == 3 ? 129 : 0);
                    EntityQueryable<Draft3Proxy<Integer, String, BigDecimal>, Draft3<Integer, String, BigDecimal>> projected = projection(rows, reader).toCteAs("stream_rows").orderBy(p -> p.value1().asc());
                    if (scenario == 3) {
                        RuntimeException thrown = assertThrows(RuntimeException.class, () -> read(projected.getClientQueryable(), Draft3::getValue1,
                                row -> assertRow(row.getValue1(), row.getValue2(), row.getValue3(), ""), Integer.MAX_VALUE));
                        assertTrue(hasCause(thrown, reader.failure));
                        assertEquals(129, reader.calls);
                    } else {
                        int consumed = scenario == 2 ? 65 : ROWS;
                        QueryResourceContract.ReadSummary summary = read(projected.getClientQueryable(), Draft3::getValue1,
                                row -> assertRow(row.getValue1(), row.getValue2(), row.getValue3(), ""), scenario == 2 ? consumed : Integer.MAX_VALUE);
                        assertEquals(new ReadSummary(consumed, checksum(consumed)), summary);
                        assertEquals(consumed, reader.calls);
                    }
                }
                // 必须先于事务归还连接检查；Hikari 的归还清理不能掩盖 JdbcStreamResult 漏关句柄。
                assertEquals(1, pool.getHikariPoolMXBean().getActiveConnections());
                resources.assertReleased();
                transaction.rollback();
            }
            assertEquals(beforeSql + 1, resources.sqlExecutions());
            assertEquals(beforeJdbc + 1, resources.jdbcExecutions());
            JdbcResourceProbe.Execution executed = resources.execution(beforeJdbc);
            assertEquals(FETCH, executed.fetchSize());
            assertFalse(executed.autoCommit(), "PostgreSQL cursor fetching needs an explicit transaction");
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, executed.resultSetType());
            reusable(rows, pool, resources);
        }
    }

    private static <T> ReadSummary read(Query<T> query, ToIntFunction<T> id, Consumer<T> check, int limit) throws SQLException {
        int count = 0;
        long total = 0;
        try (JdbcStreamResult<T> result = query.toStreamResult(statement -> statement.setFetchSize(FETCH))) {
            for (T row : result.getStreamIterable()) {
                check.accept(row);
                total += id.applyAsInt(row);
                if (++count == limit) break;
            }
        }
        return new ReadSummary(count, total);
    }

    private static void verifyConcurrentReaders(Supplier<EntityQueryable<TestProductProxy, TestProduct>> rows,
                                                HikariDataSource pool) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            ArrayList<Future<Integer>> futures = new ArrayList<Future<Integer>>();
            for (int worker = 0; worker < WORKERS; worker++) {
                int workerId = worker;
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    for (int round = 0; round < ROUNDS; round++) {
                        String tag = "w" + workerId + "/r" + round + ":";
                        if (workerId == 0 && round == 2) {
                            QueryResourceContract.ReaderProbe reader = new ReaderProbe(tag, 4);
                            EntityQueryable<Draft3Proxy<Integer, String, BigDecimal>, Draft3<Integer, String, BigDecimal>> failing = projection(() -> rows.get().where(p -> p.id().le(16)), reader).toCteAs("worker_rows");
                            assertTrue(hasCause(assertThrows(RuntimeException.class, failing::toList), reader.failure));
                            assertEquals(4, reader.calls);
                        }
                        QueryResourceContract.ReaderProbe reader = new ReaderProbe(tag, 0);
                        List<String> actual;
                        if (round % 3 == 0) {
                            EntityQueryable<TestProductProxy, TestProduct> direct = rows.get().where(p -> p.id().le(16)).orderBy(p -> p.id().asc());
                            direct.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext()
                                    .getResultValueConverterMap(true).put("name", reader);
                            List<TestProduct> result = direct.toList();
                            result.forEach(row -> assertRow(row.getId(), row.getName(), row.getAmount(), tag));
                            actual = result.stream().map(TestProduct::getName).collect(Collectors.toList());
                        } else {
                            EntityQueryable<Draft3Proxy<Integer, String, BigDecimal>, Draft3<Integer, String, BigDecimal>> projected = projection(() -> rows.get().where(p -> p.id().le(16)), reader);
                            if (round % 3 == 2) projected = projected.toCteAs("worker_rows");
                            List<Draft3<Integer, String, BigDecimal>> result = projected.orderBy(p -> p.value1().asc()).toList();
                            result.forEach(row -> assertRow(row.getValue1(), row.getValue2(), row.getValue3(), tag));
                            actual = result.stream().map(Draft3::getValue2).collect(Collectors.toList());
                        }
                        assertEquals(IntStream.rangeClosed(1, 16).mapToObj(id -> name(id) == null ? null : tag + name(id)).collect(Collectors.toList()), actual);
                        assertEquals(16, reader.calls);
                    }
                    return ROUNDS;
                }));
            }
            start.countDown();
            int completed = 0;
            for (Future<Integer> future : futures) completed += future.get(40, TimeUnit.SECONDS);
            assertEquals(WORKERS * ROUNDS, completed);
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            assertTrue(pool.getHikariPoolMXBean().getTotalConnections() <= 2);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static EntityQueryable<Draft3Proxy<Integer, String, BigDecimal>, Draft3<Integer, String, BigDecimal>>
    projection(Supplier<EntityQueryable<TestProductProxy, TestProduct>> rows, ReaderProbe reader) {
        EntityQueryable<Draft3Proxy<Integer, String, BigDecimal>, Draft3<Integer, String, BigDecimal>> projected = rows.get().select(p -> Select.DRAFT.of(p.id(), p.name(),
                ProDsl.of(p).nativeCast(p.amount(), CommonTypes.decimal(19, 4))));
        projected.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext()
                .getResultValueConverterMap(true).put("value2", reader);
        return projected;
    }

    private static void reusable(Supplier<EntityQueryable<TestProductProxy, TestProduct>> rows, HikariDataSource pool,
                                 JdbcResourceProbe resources) throws SQLException {
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        resources.assertReleased();
        assertEquals(ROWS, rows.get().count());
        try (Connection connection = pool.getConnection()) {
            assertTrue(connection.getAutoCommit());
            assertFalse(connection.isReadOnly());
        }
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        resources.assertReleased();
    }

    private static void assertRow(int id, String name, BigDecimal amount, String tag) {
        assertEquals(name(id) == null ? null : tag + name(id), name);
        if (amount(id) == null) assertNull(amount);
        else {
            assertNotNull(amount);
            assertEquals(0, amount(id).compareTo(amount));
        }
    }

    private static String name(int id) {
        return id % 7 == 0 ? null : "row:" + id;
    }

    private static BigDecimal amount(int id) {
        return id % 11 == 0 ? null : BigDecimal.valueOf(id, 2);
    }

    private static long checksum(int rows) {
        return (long) rows * (rows + 1) / 2;
    }

    private static boolean hasCause(Throwable failure, Throwable expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (current == expected) return true;
        return false;
    }

    private static final class ReadSummary {
        private final int count;
        private final long checksum;

        private ReadSummary(int count, long checksum) {
            this.count = count;
            this.checksum = checksum;
        }

        public int count() {
            return count;
        }

        public long checksum() {
            return checksum;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReadSummary)) return false;
            ReadSummary other = (ReadSummary) o;
            return count == other.count
                    && checksum == other.checksum;
        }

        @Override
        public int hashCode() {
            return Objects.hash(count, checksum);
        }

        @Override
        public String toString() {
            return "ReadSummary[count=" + count + ", checksum=" + checksum + "]";
        }
    }

    private static final class ReaderProbe implements ColumnReader {
        private final String tag;
        private final int failAt;
        private final RuntimeException failure = new IllegalStateException("expected projection reader failure");
        private int calls;

        private ReaderProbe(String tag, int failAt) {
            this.tag = tag;
            this.failAt = failAt;
        }

        @Override
        public Object read(int index, StreamResultSet result) throws SQLException {
            if (++calls == failAt) throw failure;
            String value = result.getString(index + 1);
            return value == null ? null : tag + value;
        }
    }
}
