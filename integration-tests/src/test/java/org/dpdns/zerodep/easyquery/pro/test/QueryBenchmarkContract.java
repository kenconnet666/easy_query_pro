package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.proxy.sql.Select;
import com.zaxxer.hikari.HikariDataSource;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import com.easy.query.core.common.ToSQLResult;
import java.sql.Connection;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同一物理数据、连接池和 DTO 的小型重复测量。生成/执行/物化计时，结果断言放在计时段外。
 * 不带 JDBC 观测代理，也不将一次共享开发环境的耗时转成生产吞吐承诺。
 */
final class QueryBenchmarkContract {
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 7;

    private QueryBenchmarkContract() {
    }

    static void verify(HikariDataSource pool, DatabaseConfiguration database, Consumer<ServiceCollection> extensions,
                       String table, String schema) throws Exception {
        DefaultEasyEntityQuery plain = TestClients.configured(pool, database, ignored -> {
        });
        try {
            // 第二个 client 的安装也可能失败；已取得的第一个 client 必须进入清理范围。
            DefaultEasyEntityQuery enhanced = TestClients.configured(pool, database, extensions);
            try {
                measure(pool, plain, enhanced, table, schema);
            } finally {
                shutdown(enhanced);
            }
        } finally {
            shutdown(plain);
        }
    }

    private static void measure(HikariDataSource pool, DefaultEasyEntityQuery plain, DefaultEasyEntityQuery enhanced,
                                String table, String schema) throws Exception {
        ToSQLResult rendered = projection(plain, table, schema, false).toSQLResult();
        assertTrue(rendered.getSqlContext().getParameters().isEmpty(), "The fixed benchmark source has no query parameters");
        String sql = rendered.getSQL();
        List<TestProduct> expected = jdbc(pool, sql);
        assertEquals(QueryResourceContract.ROWS, expected.size());
        assertEquals(2_100_225L, expected.stream().mapToLong(TestProduct::getId).sum());
        List<QueryBenchmarkContract.Scenario> scenarios = Arrays.asList(new Scenario("jdbc", () -> jdbc(pool, sql)),
                new Scenario("orm", () -> projection(plain, table, schema, false).toList()),
                new Scenario("pro-cast", () -> projection(enhanced, table, schema, true).toList()));
        for (int round = 0; round < WARMUPS; round++) {
            for (int offset = 0; offset < scenarios.size(); offset++) {
                QueryBenchmarkContract.Scenario scenario = scenarios.get((round + offset) % scenarios.size());
                assertRows(expected, scenario.query().call());
                assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            }
        }
        long[][] nanos = new long[scenarios.size()][SAMPLES];
        for (int round = 0; round < SAMPLES; round++) {
            // 轮换首个场景，避免每次都把第一条查询的缓存/调度成本归给同一种入口。
            for (int offset = 0; offset < scenarios.size(); offset++) {
                int index = (round + offset) % scenarios.size();
                long started = System.nanoTime();
                List<TestProduct> rows = scenarios.get(index).query().call();
                nanos[index][round] = System.nanoTime() - started;
                assertRows(expected, rows);
                assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            }
        }
        for (int index = 0; index < scenarios.size(); index++) {
            long[] sorted = nanos[index].clone();
            Arrays.sort(sorted);
            System.out.printf("QUERY_BENCHMARK dialect=%s scenario=%s rows=%d warmups=%d samples=%d minUs=%d medianUs=%d maxUs=%d rawUs=%s%n",
                    enhanced.getRuntimeContext().getService(SqlDialect.class), scenarios.get(index).name(), expected.size(), WARMUPS, SAMPLES,
                    sorted[0] / 1_000, sorted[SAMPLES / 2] / 1_000, sorted[SAMPLES - 1] / 1_000,
                    Arrays.toString(Arrays.stream(nanos[index]).map(value -> value / 1_000).toArray()));
        }
        try (Connection connection = pool.getConnection()) {
            assertTrue(connection.getAutoCommit());
            assertFalse(connection.isReadOnly());
        }
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    }

    @SuppressWarnings("deprecation") // Compare the still-public 3.2.17 DTO selector with identical output classes.
    private static Query<TestProduct> projection(DefaultEasyEntityQuery query, String table, String schema, boolean cast) {
        EntityQueryable<TestProductProxy, TestProduct> rows = query.queryable(TestProduct.class).asTable(ignored -> table);
        if (schema != null) rows = rows.asSchema(ignored -> schema);
        return rows.orderBy(p -> p.id().asc()).select(TestProduct.class, p -> Select.of(
                p.id().as("id"), p.name().as("name"),
                cast ? ProDsl.of(p).nativeCast(p.amount(), CommonTypes.decimal(19, 4)).as("amount") : p.amount().as("amount")));
    }

    private static List<TestProduct> jdbc(HikariDataSource pool, String sql) throws SQLException {
        // SQL 来自同一普通 ORM 投影，且已确认没有参数；不解析或重写它，也不作为通用原生执行器。
        try (Connection connection = pool.getConnection(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            ArrayList<TestProduct> rows = new ArrayList<TestProduct>();
            while (result.next())
                rows.add(new TestProduct(result.getInt(1), result.getString(2), result.getBigDecimal(3)));
            return rows;
        }
    }

    private static void assertRows(List<TestProduct> expected, List<TestProduct> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            TestProduct left = expected.get(index);
            TestProduct right = actual.get(index);
            assertEquals(left.getId(), right.getId());
            assertEquals(left.getName(), right.getName());
            if (left.getAmount() == null) assertNull(right.getAmount());
            else {
                assertNotNull(right.getAmount());
                assertEquals(0, left.getAmount().compareTo(right.getAmount()));
            }
        }
    }

    private static void shutdown(DefaultEasyEntityQuery query) {
        try {
            query.getRuntimeContext().getEasyTimeJobManager().shutdown();
        } finally {
            query.getRuntimeContext().getShardingExecutorService().getExecutorService().shutdown();
        }
    }

    private static final class Scenario {
        private final String name;
        private final Callable<List<TestProduct>> query;

        private Scenario(String name, Callable<List<TestProduct>> query) {
            this.name = name;
            this.query = query;
        }

        public String name() {
            return name;
        }

        public Callable<List<TestProduct>> query() {
            return query;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Scenario)) return false;
            Scenario other = (Scenario) o;
            return Objects.equals(name, other.name)
                    && Objects.equals(query, other.query);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, query);
        }

        @Override
        public String toString() {
            return "Scenario[name=" + name + ", query=" + query + "]";
        }
    }
}
