package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.enums.SQLExecuteStrategyEnum;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.insert.EntityInsertable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import java.util.Arrays;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class HanaConflictWriteIT {
    private static final String COLUMNS = "\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(255), \"amount\" DECIMAL(19,4)";

    @Test
    void nativeMergePreservesPreparedValuesAndAffectedRowCounts() throws SQLException {
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("MERGE_NATIVE", COLUMNS);
             Statement statement = table.connection().createStatement()) {
            String sql = "MERGE INTO \"" + table.name() + "\" AS target USING (SELECT ? AS \"id\", ? AS \"name\", ? AS \"amount\" FROM DUMMY) AS source"
                    + " ON target.\"id\" = source.\"id\" WHEN MATCHED THEN UPDATE SET target.\"name\" = source.\"name\", target.\"amount\" = source.\"amount\""
                    + " WHEN NOT MATCHED THEN INSERT (\"id\",\"name\",\"amount\") VALUES (source.\"id\",source.\"name\",source.\"amount\")";
            try (PreparedStatement merge = table.connection().prepareStatement(sql)) {
                merge.setInt(1, 1);
                merge.setString(2, "O'Reilly 中文😀");
                merge.setBigDecimal(3, new BigDecimal("1234567890123.4567"));
                assertEquals(1, merge.executeUpdate());
                merge.setString(2, "updated");
                merge.setBigDecimal(3, new BigDecimal("-1234567890123.4567"));
                assertEquals(1, merge.executeUpdate());
            }
            try (ResultSet rows = statement.executeQuery("SELECT \"name\",\"amount\" FROM \"" + table.name() + "\"")) {
                assertTrue(rows.next());
                assertEquals("updated", rows.getString(1));
                assertEquals(new BigDecimal("-1234567890123.4567"), rows.getBigDecimal(2));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void existingConflictApiUpdatesOnlySelectedColumnsAndIgnoresMatches() throws SQLException {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("MERGE_API", COLUMNS)) {
            assertEquals(1L, query.insertable(new TestProduct(1, "original 中文😀", BigDecimal.ONE)).asTable(t -> table.name())
                    .onConflictThen(p -> p.FETCHER.amount()).executeRows());
            TestProduct updated = new TestProduct(1, "O'Reilly 中文😀", new BigDecimal("12.3456"));
            EntityInsertable<TestProductProxy, TestProduct> update = query.insertable(updated).asTable(t -> table.name())
                    .onConflictThen(p -> p.FETCHER.amount());
            assertEquals(update.toSQL(updated), update.toSQL(updated));
            assertTrue(update.toSQL(updated).startsWith("MERGE INTO"));
            assertFalse(update.toSQL(updated).contains("Reilly"));
            assertEquals(1L, update.executeRows());
            assertEquals(1L, update.executeRows());
            assertEquals(0L, query.insertable(new TestProduct(1, "ignored", BigDecimal.TEN)).asTable(t -> table.name())
                    .onConflictThen(null).executeRows());
            TestProduct stored = query.queryable(TestProduct.class).asTable(t -> table.name()).singleNotNull();
            assertEquals("original 中文😀", stored.getName());
            assertEquals(new BigDecimal("12.3456"), stored.getAmount());
        }
    }

    @Test
    void explicitUniqueIndexCanMatchAnotherPrimaryKeyWithoutChangingIt() throws SQLException {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("MERGE_UNIQUE", COLUMNS);
             Statement statement = table.connection().createStatement()) {
            statement.execute("CREATE UNIQUE INDEX \"" + table.name() + "_UQ\" ON \"" + table.name() + "\" (\"name\")");
            query.insertable(new TestProduct(1, "same", BigDecimal.ONE)).asTable(t -> table.name()).executeRows();
            assertEquals(1L, query.insertable(new TestProduct(2, "same", BigDecimal.TEN)).asTable(t -> table.name())
                    .onConflictThen(p -> p.FETCHER.id().amount(), p -> p.FETCHER.name()).executeRows());
            TestProduct stored = query.queryable(TestProduct.class).asTable(t -> table.name()).singleNotNull();
            assertEquals(1, stored.getId());
            assertEquals(0, BigDecimal.TEN.compareTo(stored.getAmount()));
            // 忽略指定冲突不能吞掉其他唯一约束错误。
            assertThrows(RuntimeException.class, () -> query.insertable(new TestProduct(3, "same", BigDecimal.ONE))
                    .asTable(t -> table.name()).onConflictThen(null, p -> p.FETCHER.id()).executeRows());
            assertEquals(1L, query.queryable(TestProduct.class).asTable(t -> table.name()).count());
        }
    }

    @Test
    void compositeKeysAndRepeatedBatchInputsRetainNativeOrderingAndCounts() throws SQLException {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("MERGE_COMPOSITE",
                "\"id\" INTEGER, \"name\" NVARCHAR(255), \"amount\" DECIMAL(19,4), PRIMARY KEY (\"id\",\"name\")")) {
            List<TestProduct> values = Arrays.asList(new TestProduct(1, "A", BigDecimal.ONE), new TestProduct(1, "A", BigDecimal.TEN),
                    new TestProduct(1, "B", new BigDecimal("3")));
            assertEquals(3L, query.insertable(values).asTable(t -> table.name()).batch(true)
                    .onConflictThen(p -> p.FETCHER.amount(), p -> p.FETCHER.id().name()).executeRows());
            List<TestProduct> stored = query.queryable(TestProduct.class).asTable(t -> table.name()).orderBy(p -> p.name().asc()).toList();
            assertEquals(2, stored.size());
            assertEquals(0, BigDecimal.TEN.compareTo(stored.get(0).getAmount()));
            assertEquals(0, new BigDecimal("3").compareTo(stored.get(1).getAmount()));
        }
    }

    @Test
    void allColumnsStrategyCanUpdateExplicitNullValues() throws SQLException {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("MERGE_NULL", COLUMNS)) {
            query.insertable(new TestProduct(1, "before", BigDecimal.ONE)).asTable(t -> table.name()).executeRows();
            assertEquals(1L, query.insertable(new TestProduct(1, null, null)).asTable(t -> table.name())
                    .setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).onConflictThen(p -> p.FETCHER.name().amount()).executeRows());
            TestProduct stored = query.queryable(TestProduct.class).asTable(t -> table.name()).singleNotNull();
            assertNull(stored.getName());
            assertNull(stored.getAmount());
        }
    }

    @Test
    void nonUniqueConflictTargetsAndSessionKeyBackfillAreRejectedBeforeWrites() throws SQLException {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("MERGE_GUARDS", COLUMNS)) {
            assertThrows(RuntimeException.class, () -> query.insertable(new TestProduct(1, "not unique", BigDecimal.ONE))
                    .asTable(t -> table.name()).onConflictThen(p -> p.FETCHER.amount(), p -> p.FETCHER.name()).executeRows());
            assertThrows(UnsupportedOperationException.class, () -> query.insertable(new TestProduct(1, "key", BigDecimal.ONE))
                    .asTable(t -> table.name()).onConflictThen(p -> p.FETCHER.amount()).executeRows(true));
            assertEquals(0L, query.queryable(TestProduct.class).asTable(t -> table.name()).count());
        }
    }

    @Test
    void failedMergeBatchCanBeRolledBackAndTheClientRemainsUsable() throws SQLException {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("MERGE_ROLLBACK", COLUMNS + ", CHECK (\"amount\" >= 0)")) {
            query.insertable(new TestProduct(1, "before", BigDecimal.ONE)).asTable(t -> table.name()).executeRows();
            try (Transaction transaction = query.getEasyQueryClient().beginTransaction()) {
                List<TestProduct> batch = Arrays.asList(new TestProduct(1, "updated", BigDecimal.TEN),
                        new TestProduct(2, "invalid", new BigDecimal("-1")));
                assertThrows(RuntimeException.class, () -> query.insertable(batch).asTable(t -> table.name()).batch(true)
                        .onConflictThen(p -> p.FETCHER.name().amount()).executeRows());
                transaction.rollback();
            }
            TestProduct stored = query.queryable(TestProduct.class).asTable(t -> table.name()).singleNotNull();
            assertEquals("before", stored.getName());
            assertEquals(0, BigDecimal.ONE.compareTo(stored.getAmount()));
            assertEquals(1L, query.insertable(new TestProduct(2, "recovered", BigDecimal.TEN)).asTable(t -> table.name())
                    .onConflictThen(p -> p.FETCHER.amount()).executeRows());
        }
    }

    @Test
    void concurrentMergesPreserveOneRowAndSurfaceNativeUniqueConflicts() throws Exception {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("MERGE_CONCURRENT", COLUMNS)) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 2; round++) {
                    CountDownLatch start = new CountDownLatch(1);
                    ArrayList<Future<Long>> tasks = new ArrayList<Future<Long>>();
                    for (int writer = 0; writer < 2; writer++) {
                        int value = round * 10 + writer + 1;
                        tasks.add(executor.submit(() -> {
                            assertTrue(start.await(10, TimeUnit.SECONDS));
                            try {
                                return query.insertable(new TestProduct(1, "writer-" + value, BigDecimal.valueOf(value)))
                                        .asTable(t -> table.name()).onConflictThen(p -> p.FETCHER.name().amount()).executeRows();
                            } catch (RuntimeException failure) {
                                // 并发首次插入可能发生原生唯一键竞争；只接受 301，不能吞连接或其他错误。
                                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                                    if (cause instanceof SQLException && ((SQLException) cause).getErrorCode() == 301) return -1L;
                                }
                                throw failure;
                            }
                        }));
                    }
                    start.countDown();
                    ArrayList<Long> outcomes = new ArrayList<Long>();
                    for (Future<Long> task : tasks) outcomes.add(task.get(30, TimeUnit.SECONDS));
                    assertTrue(outcomes.contains(1L));
                    assertTrue(outcomes.stream().allMatch(rows -> rows == 1 || rows == -1));
                    if (round == 1) assertEquals(Arrays.asList(1L, 1L), outcomes);
                    TestProduct stored = query.queryable(TestProduct.class).asTable(t -> table.name()).singleNotNull();
                    assertEquals(1, stored.getId());
                    assertTrue(stored.getName().equals("writer-" + (round * 10 + 1))
                            || stored.getName().equals("writer-" + (round * 10 + 2)));
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }
}
