package example.easyquery;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.sql.Select;
import example.easyquery.proxy.ProductProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactConsumerTest {
    private static final List<Class<?>> LIBRARIES = List.of(ProDsl.class, HanaDatabaseConfiguration.class, HanaDsl.class, MySqlDsl.class, PgSqlDsl.class);

    @Test
    void librariesComeFromJarsAndTheConsumerGeneratesItsOwnProxy() throws Exception {
        for (Class<?> type : LIBRARIES) {
            var location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertTrue(location.getFileName().toString().endsWith(".jar"), () -> type.getName() + " came from " + location);
            assertTrue(Files.isRegularFile(location), location.toString());
        }
        assertTrue(Files.isRegularFile(Path.of("target/generated-sources/annotations/example/easyquery/proxy/ProductProxy.java")));
        assertEquals(Product.class, ProductProxy.createTable().getEntityClass());
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.dpdns.zerodep.easyquery.pro.test.TestClients"));
    }

    @Test
    @EnabledIfSystemProperty(named = "consumer.classifiers", matches = "true")
    void sourcesAndJavadocsResolveAsRealDependencyClassifiers() throws Exception {
        try (var files = Files.list(Path.of("target/classifier-artifacts"))) {
            assertEquals(10L, files.filter(file -> file.toString().endsWith(".jar")).count());
        }
        for (Class<?> type : LIBRARIES) {
            var binary = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString();
            String base = binary.substring(0, binary.length() - ".jar".length());
            String entry = type.getName().replace('.', '/');
            try (var sources = new ZipFile(Path.of("target/classifier-artifacts", base + "-sources.jar").toFile());
                 var javadoc = new ZipFile(Path.of("target/classifier-artifacts", base + "-javadoc.jar").toFile())) {
                assertNotNull(sources.getEntry(entry + ".java"), type.getName());
                assertFalse(sources.stream().anyMatch(file -> file.getName().endsWith(".class")));
                assertNotNull(javadoc.getEntry("index.html"));
                assertNotNull(javadoc.getEntry(entry + ".html"), type.getName());
                if (type == ProDsl.class) {
                    try (var source = sources.getInputStream(sources.getEntry(entry + ".java"));
                         var html = javadoc.getInputStream(javadoc.getEntry(entry + ".html"))) {
                        assertTrue(new String(source.readAllBytes(), StandardCharsets.UTF_8).contains("共同增强入口"));
                        String documentation = new String(html.readAllBytes(), StandardCharsets.UTF_8);
                        assertTrue(documentation.contains("共同增强入口"));
                        assertTrue(documentation.contains("denseRank"), "Current public methods must be present in API documentation");
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Clients.Database.class)
    void freshAptQueriesCompileWithCommonAndSelectedNativeApis(Clients.Database database) {
        var query = Clients.create(offline(), database);
        try {
            var rendered = ProductQueries.named(query, "consumer_product", "O'Reilly 中文").toSQLResult();
            assertFalse(rendered.getSQL().contains("O'Reilly"), rendered.getSQL());
            assertTrue(rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList().contains("O'Reilly 中文"));
            assertTrue(ProductQueries.atLeast(query, "consumer_product", new BigDecimal("12.30")).toSQL().contains("CAST("));
            String nativeSql = switch (database) {
                case MYSQL -> query.queryable(ProductProxy.createTable()).select(p -> Select.DRAFT.of(
                        MySqlDsl.of(p).value(Duration.ofHours(27), MySqlTypes.durationTime(6)))).toSQL();
                case POSTGRES -> query.queryable(ProductProxy.createTable()).select(p -> Select.DRAFT.of(
                        PgSqlDsl.of(p).value(PgInterval.of(1, 2, 3), PgSqlTypes.interval()))).toSQL();
                case HANA -> query.queryable(ProductProxy.createTable()).select(p -> Select.DRAFT.of(
                        HanaDsl.of(p).nativeCast(p.name(), HanaTypes.alphanum(12)))).toSQL();
            };
            assertTrue(nativeSql.contains("?") || nativeSql.contains("CAST("), nativeSql);
        } finally {
            query.getRuntimeContext().getShardingExecutorService().getExecutorService().shutdown();
        }
    }

    private static DataSource offline() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("SQL construction must not open JDBC: " + method.getName());
                });
    }
}
