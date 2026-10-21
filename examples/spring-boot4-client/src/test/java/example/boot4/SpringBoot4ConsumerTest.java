package example.boot4;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.sql.starter.conn.SpringConnectionManager;
import com.easy.query.sql.starter.option.DatabaseEnum;
import com.zaxxer.hikari.HikariDataSource;
import example.boot4.proxy.LedgerEntryProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.hana.function.HanaSQLFunc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starts real Boot 4 applications, so every auto-configuration listed by the upstream starters takes part.
 */
class SpringBoot4ConsumerTest {
    @ParameterizedTest
    @EnumSource(value = DatabaseEnum.class, names = {"MYSQL", "PGSQL", "SQL92"})
    void fullAutoConfigurationInstallsProServices(DatabaseEnum database) {
        ExecutorService executor;
        try (ConfigurableApplicationContext context = application(OfflineDataSource.class,
                "easy-query.database=" + database, "easy-query.default-schema=consumer_schema")) {
            assertTrue(SpringBootVersion.getVersion().startsWith("4."), SpringBootVersion.getVersion());
            assertEquals(1, context.getBeansOfType(EasyQueryClient.class).size());
            var query = context.getBean(EasyEntityQuery.class);
            var runtime = query.getRuntimeContext();
            assertSame(context.getBean(EasyQueryClient.class), query.getEasyQueryClient());
            assertInstanceOf(SpringConnectionManager.class, runtime.getConnectionManager());
            var expected = switch (database) {
                case MYSQL -> SqlDialect.MYSQL;
                case PGSQL -> SqlDialect.POSTGRESQL;
                default -> SqlDialect.HANA;
            };
            assertEquals(expected, runtime.getService(SqlDialect.class));
            if (database == DatabaseEnum.SQL92) assertInstanceOf(HanaSQLFunc.class, runtime.fx());
            var sql = query.queryable(LedgerEntryProxy.createTable())
                    .where(entry -> ProDsl.of(entry).nullSafeEqualsValue(entry.label(), "O'Reilly").eq(true)).toSQL();
            assertTrue(sql.contains("consumer_schema"), sql);
            assertFalse(sql.contains("O'Reilly"), sql);
            executor = runtime.getShardingExecutorService().getExecutorService();
        }
        assertTrue(executor.isShutdown(), "Closing Spring must shut down this client's worker executor");
    }

    @Test
    @EnabledIfSystemProperty(named = "consumer.database", matches = "mysql|postgres|hana")
    void springTransactionsShareConnectionsWithJdbcTemplate() {
        String selected = System.getProperty("consumer.database");
        String prefix = switch (selected) {
            case "mysql" -> "EQP_MYSQL";
            case "postgres" -> "EQP_PG";
            default -> "EQP_HANA";
        };
        String database = switch (selected) {
            case "mysql" -> "mysql";
            case "postgres" -> "pgsql";
            default -> "sql92";
        };
        HikariDataSource pool;
        try (ConfigurableApplicationContext context = application(LiveComponents.class, "easy-query.database=" + database,
                "spring.datasource.url=" + required(prefix + "_JDBC_URL"), "spring.datasource.username=" + required(prefix + "_USER"),
                "spring.datasource.password=" + required(prefix + "_PASSWORD"), "spring.datasource.hikari.maximum-pool-size=2")) {
            pool = context.getBean(HikariDataSource.class);
            var query = context.getBean(EasyEntityQuery.class);
            var dialect = query.getRuntimeContext().getQueryConfiguration().getDialect();
            String table = "EQP_BOOT4_" + UUID.randomUUID().toString().replace("-", "");
            String quoted = dialect.getQuoteName(table);
            var jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("CREATE TABLE " + quoted + " (" + dialect.getQuoteName("id") + " INTEGER PRIMARY KEY,"
                    + dialect.getQuoteName("label") + " " + CommonTypes.unicodeText(255).ddlType(query.getRuntimeContext().getService(SqlDialect.class)) + ","
                    + dialect.getQuoteName("amount") + " DECIMAL(19,2))");
            try {
                String insert = "INSERT INTO " + quoted + " (" + dialect.getQuoteName("id") + "," + dialect.getQuoteName("label") + ","
                        + dialect.getQuoteName("amount") + ") VALUES(?,?,?)";
                assertThrows(RollbackRequested.class, () -> context.getBean(TransactionalWriter.class).writeThenFail(table, insert));
                assertEquals(0L, query.queryable(LedgerEntry.class).asTable(table).count());
                context.getBean(TransactionTemplate.class).executeWithoutResult(status ->
                        assertEquals(1L, query.insertable(new LedgerEntry(3, "committed 中文", new BigDecimal("12.30"))).asTable(table).executeRows()));
                var saved = query.queryable(LedgerEntry.class).asTable(table).singleNotNull();
                assertEquals("committed 中文", saved.getLabel());
                assertEquals(new BigDecimal("12.30"), saved.getAmount());
            } finally {
                jdbc.execute("DROP TABLE " + quoted);
            }
        }
        assertTrue(pool.isClosed(), "Spring owns and closes the auto-configured Hikari pool");
    }

    private static ConfigurableApplicationContext application(Class<?> components, String... properties) {
        return new SpringApplicationBuilder(Application.class, components).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "spring.sql.init.mode=never", "easy-query.enable=true",
                        "easy-query.print-sql=false", "easy-query.start-time-job=false")
                .properties(properties)
                .run();
    }

    private static String required(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Missing required test environment variable: " + variable);
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    static class OfflineDataSource {
        @Bean
        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "toString" -> "offline";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        case "isWrapperFor" -> false;
                        default -> throw new AssertionError("Context initialization must not open JDBC: " + method.getName());
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class LiveComponents {
        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactions) {
            return new TransactionTemplate(transactions);
        }

        @Bean
        TransactionalWriter transactionalWriter(EasyEntityQuery query, JdbcTemplate jdbc) {
            return new TransactionalWriter(query, jdbc);
        }
    }

    public static class TransactionalWriter {
        private final EasyEntityQuery query;
        private final JdbcTemplate jdbc;

        TransactionalWriter(EasyEntityQuery query, JdbcTemplate jdbc) {
            this.query = query;
            this.jdbc = jdbc;
        }

        @Transactional
        public void writeThenFail(String table, String insertSql) {
            assertEquals(1, jdbc.update(insertSql, 1, "jdbc 中文", new BigDecimal("1.20")));
            assertEquals(1L, query.queryable(LedgerEntry.class).asTable(table).count(), "ORM must see the same uncommitted JDBC row");
            assertEquals(1L, query.insertable(new LedgerEntry(2, "orm 中文", new BigDecimal("2.30"))).asTable(table).executeRows());
            throw new RollbackRequested();
        }
    }

    private static class RollbackRequested extends RuntimeException {
    }
}
