package example.springquery;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.types.handler.StringTypeHandler;
import com.easy.query.core.basic.thread.ShardingExecutorService;
import com.easy.query.core.configuration.EasyQueryOption;
import com.easy.query.core.inject.ServiceProvider;
import com.easy.query.core.job.DefaultEasyTimeJobManager;
import com.easy.query.core.job.EasyTimeJobManager;
import com.easy.query.sql.starter.EasyQueryStarterAutoConfiguration;
import com.easy.query.sql.starter.EasyQueryStarterBuildAutoConfiguration;
import com.easy.query.sql.starter.SpringBootStarterBuilder;
import com.easy.query.sql.starter.config.EasyQueryProperties;
import com.easy.query.sql.starter.config.EasyQueryInitializeOption;
import com.easy.query.sql.starter.config.JdbcTypeHandlerReplaceConfigurer;
import com.easy.query.sql.starter.conn.SpringConnectionManager;
import com.easy.query.sql.starter.conn.SpringDataSourceUnitFactory;
import com.easy.query.sql.starter.option.DatabaseEnum;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import example.springquery.proxy.LedgerEntryProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgIntervalTypeHandler;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonbColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.hana.function.HanaSQLFunc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SpringConsumerTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EasyQueryStarterAutoConfiguration.class,
                    EasyQueryStarterBuildAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withUserConfiguration(ProConfiguration.class, ApplicationComponents.class)
            .withPropertyValues("easy-query.enable=true", "easy-query.print-sql=false",
                    "easy-query.start-time-job=false", "easy-query.result-size-limit=73");

    @Test
    void unknownDatabaseFailsBeforeTheStarterCustomizerCanInstallHana() {
        var called = new AtomicBoolean();
        var properties = new EasyQueryProperties();
        var error = assertThrows(UnsupportedOperationException.class, () -> SpringBootStarterBuilder.buildClient(
                offline(), properties, null, services -> called.set(true)));
        assertTrue(error.getMessage().contains("database dialect"));
        assertFalse(called.get(), "An UNKNOWN database must not appear to accept a late HANA customizer");
    }

    @Test
    void anApplicationJsonConverterKeepsItsIdentityAlongsideMissingDefaults() {
        var supplied = new PgJsonbColumnConverter();
        runner.withBean(DataSource.class, SpringConsumerTest::offline)
                .withBean(PgJsonbColumnConverter.class, () -> supplied)
                .withPropertyValues("easy-query.database=pgsql")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var configuration = context.getBean(EasyQueryClient.class).getRuntimeContext().getQueryConfiguration();
                    assertSame(supplied, configuration.getColumnValueSQLConverter(PgJsonbColumnConverter.class));
                    assertNotNull(configuration.getColumnValueSQLConverter(PgJsonColumnConverter.class));
                    var metadata = context.getBean(EasyQueryClient.class).getRuntimeContext().getEntityMetadataManager().getEntityMetadata(JsonColumns.class);
                    assertSame(supplied, metadata.getColumnNotNull("document").getColumnValueSQLConverter());
                    assertSame(configuration.getColumnValueSQLConverter(PgJsonColumnConverter.class),
                            metadata.getColumnNotNull("json").getColumnValueSQLConverter());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedComponentInstallationClosesUnregisteredClientWorkers(boolean cleanupAlsoFails) {
        var original = new IllegalStateException("application component failed");
        var cleanup = cleanupAlsoFails ? new IllegalStateException("time job shutdown failed") : null;
        var properties = new EasyQueryProperties();
        properties.setDatabase(DatabaseEnum.MYSQL);
        properties.setStartTimeJob(true);
        var components = new EasyQueryInitializeOption();
        components.getJdbcTypeHandlerMap().put("broken", new TrackingStringHandler() {
            @Override
            public Set<Class<?>> allowTypes() {
                throw original;
            }
        });
        var jobs = new AtomicReference<TrackingTimeJobs>();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var thrown = assertThrows(IllegalStateException.class, () -> new ProConfiguration().easyQueryClient(
                    offline(), properties, components, services -> {
                        services.addServiceFactory(EasyTimeJobManager.class, provider -> {
                            var manager = new TrackingTimeJobs(provider.getService(EasyQueryOption.class), provider, cleanup);
                            jobs.set(manager);
                            return manager;
                        });
                        services.addService(ShardingExecutorService.class, (ShardingExecutorService) () -> worker);
                    }));
            assertSame(original, thrown);
            assertTrue(jobs.get().stopped.get(), "The client has not become a Spring bean yet, so its factory must stop the timer");
            assertTrue(worker.isShutdown());
            assertEquals(cleanupAlsoFails ? List.of(cleanup) : List.of(), List.of(original.getSuppressed()));
        } finally {
            // 红测也必须清理真实启动的非 daemon 定时线程，不能靠 surefire 退出掩盖泄漏。
            if (jobs.get() != null) jobs.get().stopAfterTest();
            worker.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseEnum.class, names = {"MYSQL", "PGSQL", "SQL92"})
    void starterOptionsComponentsAndClientScopedServicesSurviveTheIntegration(DatabaseEnum database) {
        var executor = new AtomicReference<ExecutorService>();
        runner.withBean(DataSource.class, SpringConsumerTest::offline)
                .withPropertyValues("easy-query.database=" + database, "easy-query.default-schema=consumer_schema")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(EasyQueryClient.class).size());
                    assertEquals(1, context.getBeansOfType(EasyEntityQuery.class).size());
                    var core = context.getBean(EasyQueryClient.class);
                    var query = context.getBean(EasyEntityQuery.class);
                    assertSame(core, query.getEasyQueryClient());
                    var runtime = core.getRuntimeContext();
                    assertInstanceOf(SpringConnectionManager.class, runtime.getConnectionManager());
                    assertInstanceOf(SpringDataSourceUnitFactory.class, runtime.getDataSourceUnitFactory());
                    assertEquals(73, runtime.getQueryConfiguration().getEasyQueryOption().getResultSizeLimit());
                    assertEquals("consumer_schema", runtime.getQueryConfiguration().getEasyQueryOption().getDefaultSchema());
                    assertSame(context.getBean(TrackingStringHandler.class), runtime.getJdbcTypeHandlerManager().getHandler(String.class));
                    var expected = switch (database) {
                        case MYSQL -> SqlDialect.MYSQL;
                        case PGSQL -> SqlDialect.POSTGRESQL;
                        case SQL92 -> SqlDialect.HANA;
                        default -> throw new AssertionError(database);
                    };
                    assertEquals(expected, runtime.getService(SqlDialect.class));
                    if (database == DatabaseEnum.SQL92) {
                        assertInstanceOf(HanaSQLFunc.class, runtime.fx());
                        assertSame(context.getBean(HanaOptions.class), runtime.getService(HanaOptions.class));
                        assertEquals(1024, runtime.getService(HanaOptions.class).maxJsonValueUtf16Units());
                    }
                    if (database == DatabaseEnum.PGSQL)
                        assertInstanceOf(PgIntervalTypeHandler.class, runtime.getJdbcTypeHandlerManager().getHandler(PgInterval.class));
                    var sql = query.queryable(LedgerEntryProxy.createTable())
                            .where(entry -> ProDsl.of(entry).nullSafeEqualsValue(entry.label(), "O'Reilly").eq(true)).toSQL();
                    assertTrue(sql.contains("consumer_schema"), sql);
                    assertFalse(sql.contains("O'Reilly"), sql);
                    for (Class<?> type : List.of(ProDsl.class, SpringBootStarterBuilder.class))
                        assertTrue(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString().endsWith(".jar"));
                    assertTrue(Files.isRegularFile(Path.of("target/generated-sources/annotations/example/springquery/proxy/LedgerEntryProxy.java")));
                    executor.set(runtime.getShardingExecutorService().getExecutorService());
                });
        assertTrue(executor.get().isShutdown(), "Closing Spring must shut down this client's worker executor");
    }

    @Test
    @EnabledIfSystemProperty(named = "consumer.database", matches = "mysql|postgres|hana")
    void springTransactionsShareJdbcTemplateConnectionsAndRestorePoolState() {
        var selected = System.getProperty("consumer.database");
        var database = switch (selected) {
            case "mysql" -> DatabaseEnum.MYSQL;
            case "postgres" -> DatabaseEnum.PGSQL;
            case "hana" -> DatabaseEnum.SQL92;
            default -> throw new AssertionError(selected);
        };
        var dataSource = new AtomicReference<HikariDataSource>();
        var executor = new AtomicReference<ExecutorService>();
        runner.withUserConfiguration(LiveDataSource.class)
                .withPropertyValues("easy-query.database=" + database)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var pool = context.getBean(HikariDataSource.class);
                    dataSource.set(pool);
                    var query = context.getBean(EasyEntityQuery.class);
                    var runtime = query.getRuntimeContext();
                    executor.set(runtime.getShardingExecutorService().getExecutorService());
                    var dialect = runtime.getQueryConfiguration().getDialect();
                    String table = "EQP_SPRING_" + UUID.randomUUID().toString().replace("-", "");
                    String quoted = dialect.getQuoteName(table);
                    var jdbc = new JdbcTemplate(pool);
                    String insert = "INSERT INTO " + quoted + " (" + dialect.getQuoteName("id") + ","
                            + dialect.getQuoteName("label") + "," + dialect.getQuoteName("amount") + ") VALUES(?,?,?)";
                    try (var owner = pool.getConnection(); var statement = owner.createStatement()) {
                        statement.execute("CREATE TABLE " + quoted + " (" + dialect.getQuoteName("id") + " INTEGER PRIMARY KEY,"
                                + dialect.getQuoteName("label") + " " + CommonTypes.unicodeText(255).ddlType(runtime.getService(SqlDialect.class)) + ","
                                + dialect.getQuoteName("amount") + " DECIMAL(19,2))");
                        try {
                            assertThrows(RollbackRequested.class, () -> context.getBean(TransactionalWriter.class).writeThenFail(table, insert));
                            assertEquals(0L, query.queryable(LedgerEntry.class).asTable(table).count());
                            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM " + quoted, Long.class));
                            context.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
                                assertEquals(1L, query.insertable(new LedgerEntry(3, "committed", new BigDecimal("12.30"))).asTable(table).executeRows());
                                assertEquals(1, jdbc.update("UPDATE " + quoted + " SET " + dialect.getQuoteName("label") + "=? WHERE "
                                        + dialect.getQuoteName("id") + "=?", "updated 中文", 3));
                                assertEquals(1, pool.getHikariPoolMXBean().getActiveConnections() - 1,
                                        "The transaction must borrow one shared connection in addition to the owner");
                            });
                            var saved = query.queryable(LedgerEntry.class).asTable(table).singleNotNull();
                            assertEquals("updated 中文", saved.getLabel());
                            assertEquals(new BigDecimal("12.30"), saved.getAmount());
                            assertTrue(context.getBean(TrackingStringHandler.class).reads.get() > 0);
                            assertEquals(1, pool.getHikariPoolMXBean().getActiveConnections());
                            try (var reused = pool.getConnection()) {
                                assertTrue(reused.getAutoCommit());
                            }
                        } finally {
                            statement.execute("DROP TABLE " + quoted);
                        }
                    }
                    assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
                });
        assertTrue(dataSource.get().isClosed(), "Spring owns and closes this example's Hikari pool");
        assertTrue(executor.get().isShutdown());
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationComponents {
        @Bean
        HanaOptions hanaOptions() {
            return new HanaOptions(1024);
        }

        @Bean
        TrackingStringHandler applicationStringHandler() {
            return new TrackingStringHandler();
        }

        @Bean
        TransactionalWriter transactionalWriter(EasyEntityQuery query, DataSource source) {
            return new TransactionalWriter(query, new JdbcTemplate(source));
        }
    }

    public static class TrackingStringHandler extends StringTypeHandler implements JdbcTypeHandlerReplaceConfigurer {
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
            reads.incrementAndGet();
            return super.getValue(property, result);
        }

        @Override
        public boolean replace() {
            return true;
        }

        @Override
        public Set<Class<?>> allowTypes() {
            return Set.of(String.class);
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
            assertEquals(2L, query.queryable(LedgerEntry.class).asTable(table).count());
            throw new RollbackRequested();
        }
    }

    private static class RollbackRequested extends RuntimeException {
    }

    @Table("consumer_json")
    public static class JsonColumns {
        @Column(dbType = "JSONB", sqlConversion = PgJsonbColumnConverter.class)
        private String document;
        @Column(dbType = "JSON", sqlConversion = PgJsonColumnConverter.class)
        private String json;

        public String getDocument() {
            return document;
        }

        public void setDocument(String document) {
            this.document = document;
        }

        public String getJson() {
            return json;
        }

        public void setJson(String json) {
            this.json = json;
        }
    }

    private static class TrackingTimeJobs extends DefaultEasyTimeJobManager {
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final RuntimeException failure;

        TrackingTimeJobs(EasyQueryOption options, ServiceProvider provider, RuntimeException failure) {
            super(options, provider);
            this.failure = failure;
        }

        @Override
        public void shutdown() {
            super.shutdown();
            stopped.set(true);
            if (failure != null) throw failure;
        }

        void stopAfterTest() {
            super.shutdown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LiveDataSource {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            String prefix = switch (System.getProperty("consumer.database")) {
                case "mysql" -> "EQP_MYSQL";
                case "postgres" -> "EQP_PG";
                case "hana" -> "EQP_HANA";
                default -> throw new IllegalArgumentException("A live database must be selected");
            };
            var config = new HikariConfig();
            config.setJdbcUrl(required(prefix + "_JDBC_URL"));
            config.setUsername(required(prefix + "_USER"));
            config.setPassword(required(prefix + "_PASSWORD"));
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(10000);
            config.setPoolName("spring-consumer");
            return new HikariDataSource(config);
        }
    }

    private static String required(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Missing required test environment variable: " + variable);
        return value;
    }

    private static DataSource offline() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Context initialization must not open JDBC: " + method.getName());
                });
    }
}
