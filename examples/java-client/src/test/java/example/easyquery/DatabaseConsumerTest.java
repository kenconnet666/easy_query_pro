package example.easyquery;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConsumerTest {
    @Test
    @EnabledIfSystemProperty(named = "consumer.database", matches = "mysql|postgres|hana")
    void packagedClientRunsCrudAndRollsBackUsingItsOwnPool() throws Exception {
        String selected = System.getProperty("consumer.database");
        var database = Clients.Database.valueOf(selected.toUpperCase(Locale.ROOT));
        String prefix = "EQP_" + (database == Clients.Database.POSTGRES ? "PG" : database.name());
        var config = new HikariConfig();
        config.setJdbcUrl(required(prefix + "_JDBC_URL"));
        config.setUsername(required(prefix + "_USER"));
        config.setPassword(required(prefix + "_PASSWORD"));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10000);
        config.setPoolName("artifact-consumer");
        String table = "EQP_CONSUMER_" + UUID.randomUUID().toString().replace("-", "");
        try (var pool = new HikariDataSource(config)) {
            var client = Clients.create(pool, database);
            var dialect = client.getRuntimeContext().getQueryConfiguration().getDialect();
            String quotedTable = dialect.getQuoteName(table);
            String columns = dialect.getQuoteName("id") + " INTEGER PRIMARY KEY,"
                    + dialect.getQuoteName("name") + " " + CommonTypes.unicodeText(255).ddlType(client.getRuntimeContext().getService(SqlDialect.class)) + ","
                    + dialect.getQuoteName("amount") + " DECIMAL(19,2)";
            try (var owner = pool.getConnection(); var statement = owner.createStatement()) {
                statement.execute("CREATE TABLE " + quotedTable + " (" + columns + ")");
                try {
                    assertEquals(2L, client.insertable(List.of(new Product(1, "O'Reilly 中文", new BigDecimal("12.30")),
                                    new Product(2, null, new BigDecimal("20.45"))))
                            .asTable(table).executeRows());
                    assertEquals(new BigDecimal("12.30"), ProductQueries.named(client, table, "O'Reilly 中文").singleNotNull().getAmount());
                    assertEquals(2, ProductQueries.named(client, table, null).singleNotNull().getId());
                    assertEquals(List.of(1, 2), ProductQueries.atLeast(client, table, BigDecimal.TEN).toList().stream().map(Product::getId).toList());
                    var page = ProductQueries.atLeast(client, table, BigDecimal.TEN).toPageResult(2, 1);
                    assertEquals(2L, page.getTotal());
                    assertEquals(2, page.getData().get(0).getId());
                    try (var transaction = client.getEasyQueryClient().beginTransaction()) {
                        assertEquals(1L, client.updatable(Product.class).asTable(table).setColumns(p -> p.name().set("uncommitted"))
                                .where(p -> p.id().eq(1)).executeRows());
                        assertEquals(1L, ProductQueries.named(client, table, "uncommitted").count());
                        transaction.rollback();
                    }
                    assertEquals(1L, ProductQueries.named(client, table, "O'Reilly 中文").count());
                    assertEquals(1L, client.deletable(Product.class).asTable(table).allowDeleteStatement(true)
                            .where(p -> p.id().eq(2)).executeRows());
                    assertEquals(1L, ProductQueries.atLeast(client, table, BigDecimal.TEN).count());
                    assertEquals(1, pool.getHikariPoolMXBean().getActiveConnections(), "Only the fixture owner should remain borrowed");
                } finally {
                    statement.execute("DROP TABLE " + quotedTable);
                }
            } finally {
                client.getRuntimeContext().getShardingExecutorService().getExecutorService().shutdown();
            }
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            try (var reused = pool.getConnection()) {
                assertTrue(reused.getAutoCommit(), "Completed transactions must not leave pool state behind");
            }
        }
    }

    private static String required(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Missing required test environment variable: " + variable);
        return value;
    }
}
