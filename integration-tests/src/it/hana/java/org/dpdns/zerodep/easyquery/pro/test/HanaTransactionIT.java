package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import java.util.concurrent.ExecutorService;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import java.util.concurrent.Future;
import com.zaxxer.hikari.HikariDataSource;
import com.easy.query.core.api.client.EasyQueryClient;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

class HanaTransactionIT {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void forUpdateBlocksAnotherTransactionUntilCommitOrRollback(boolean commit) throws Exception {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("ROW_LOCK", "\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(255), \"amount\" DECIMAL(19,4)")) {
            query.insertable(new TestProduct(1, "locked", BigDecimal.ONE)).asTable(t -> table.name()).executeRows();
            assertThrows(IllegalStateException.class, () -> query.queryable(TestProduct.class).asTable(t -> table.name())
                    .where(p -> p.id().eq(1)).forUpdate().toList());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                try (Transaction owner = query.getEasyQueryClient().beginTransaction()) {
                    assertEquals(1, query.queryable(TestProduct.class).asTable(t -> table.name()).where(p -> p.id().eq(1))
                            .orderBy(p -> p.id().asc()).limit(1).forUpdate().singleNotNull().getId());
                    Future<Integer> waiter = executor.submit(() -> {
                        try (Transaction transaction = query.getEasyQueryClient().beginTransaction()) {
                            TestProduct row = query.queryable(TestProduct.class).asTable(t -> table.name()).where(p -> p.id().eq(1))
                                    .forUpdate().singleNotNull();
                            transaction.commit();
                            return row.getId();
                        }
                    });
                    awaitBlockedOn(table.connection(), table.name());
                    assertFalse(waiter.isDone());
                    if (commit) owner.commit();
                    else owner.rollback();
                    assertEquals(1, waiter.get(10, TimeUnit.SECONDS));
                }
                assertEquals(1L, query.queryable(TestProduct.class).asTable(t -> table.name()).count());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void supportedIsolationLevelsReachTheDatabaseAndDoNotLeakThroughThePool() throws Exception {
        try (HikariDataSource pool = HanaTestDatabase.pool(1)) {
            EasyQueryClient client = EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(pool)
                    .useDatabaseConfigure(new HanaDatabaseConfiguration()).build();
            Map<Integer, String> expected = new LinkedHashMap<>();
            expected.put(Connection.TRANSACTION_READ_COMMITTED, "READ COMMITTED");
            // revision 88 的监控视图将原生 REPEATABLE READ 同样报告为 SERIALIZABLE。
            expected.put(Connection.TRANSACTION_REPEATABLE_READ, "SERIALIZABLE");
            expected.put(Connection.TRANSACTION_SERIALIZABLE, "SERIALIZABLE");
            for (Map.Entry<Integer, String> entry : expected.entrySet()) {
                try (Transaction transaction = client.beginTransaction(entry.getKey())) {
                    String actual = client.sqlQuery("SELECT ISOLATION_LEVEL FROM M_TRANSACTIONS WHERE CONNECTION_ID = CURRENT_CONNECTION", String.class).get(0);
                    assertEquals(entry.getValue(), actual);
                    transaction.rollback();
                }
                try (Connection recycled = pool.getConnection()) {
                    assertTrue(recycled.getAutoCommit());
                    assertEquals(Connection.TRANSACTION_READ_COMMITTED, recycled.getTransactionIsolation());
                }
            }
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        }
    }

    @Test
    void unsupportedIsolationDoesNotLeakAnAcquiredConnection() throws Exception {
        try (HikariDataSource pool = HanaTestDatabase.pool(1)) {
            EasyQueryClient client = EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(pool)
                    .useDatabaseConfigure(new HanaDatabaseConfiguration()).build();
            assertThrows(RuntimeException.class, () -> {
                try (Transaction transaction = client.beginTransaction(Connection.TRANSACTION_READ_UNCOMMITTED)) {
                    client.sqlQuery("SELECT 1 FROM DUMMY", Integer.class);
                    transaction.rollback();
                }
            });
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            assertEquals(1, client.sqlQuery("SELECT 1 FROM DUMMY", Integer.class).get(0));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE})
    void isolationLevelsPreserveTheirDocumentedSnapshotVisibility(int isolation) throws Exception {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("SNAPSHOT", "\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(255), \"amount\" DECIMAL(19,4)")) {
            query.insertable(new TestProduct(1, "before", BigDecimal.ONE)).asTable(t -> table.name()).executeRows();
            try (Transaction transaction = query.getEasyQueryClient().beginTransaction(isolation)) {
                assertEquals("before", query.queryable(TestProduct.class).asTable(t -> table.name()).singleNotNull().getName());
                // 独立物理连接提交新版本，避免仅验证事务对象上记录的 isolation 数字。
                try (Statement writer = table.connection().createStatement()) {
                    assertEquals(1, writer.executeUpdate("UPDATE \"" + table.name() + "\" SET \"name\" = 'after' WHERE \"id\" = 1"));
                }
                String seen = query.queryable(TestProduct.class).asTable(t -> table.name()).singleNotNull().getName();
                assertEquals(isolation == Connection.TRANSACTION_READ_COMMITTED ? "after" : "before", seen);
                transaction.rollback();
            }
            assertEquals("after", query.queryable(TestProduct.class).asTable(t -> table.name()).singleNotNull().getName());
        }
    }

    private static void awaitBlockedOn(Connection observer, String table) throws Exception {
        // 以服务器锁等待记录为证据，避免仅凭 future 尚未结束或固定 sleep 判断锁有效。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (PreparedStatement statement = observer.prepareStatement("SELECT COUNT(*) FROM M_BLOCKED_TRANSACTIONS WHERE WAITING_TABLE_NAME = ?")) {
            statement.setString(1, table);
            while (System.nanoTime() < deadline) {
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) return;
                }
                Thread.sleep(20);
            }
        }
        fail("HANA did not report a blocked transaction for " + table);
    }
}
