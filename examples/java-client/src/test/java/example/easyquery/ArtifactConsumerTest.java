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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactConsumerTest {
    @Test
    void librariesComeFromJarsAndTheConsumerGeneratesItsOwnProxy() throws Exception {
        for (Class<?> type : List.of(ProDsl.class, HanaDatabaseConfiguration.class, HanaDsl.class, MySqlDsl.class, PgSqlDsl.class)) {
            var location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertTrue(location.getFileName().toString().endsWith(".jar"), () -> type.getName() + " came from " + location);
            assertTrue(Files.isRegularFile(location), location.toString());
        }
        assertTrue(Files.isRegularFile(Path.of("target/generated-sources/annotations/example/easyquery/proxy/ProductProxy.java")));
        assertEquals(Product.class, ProductProxy.createTable().getEntityClass());
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.dpdns.zerodep.easyquery.pro.test.TestClients"));
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
