package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamRuntimeTest {
    @ParameterizedTest
    @MethodSource("databases")
    void generatedProxyRendersBoundSqlOnJava17(DatabaseConfiguration configuration, String quote) {
        assertEquals(17, Runtime.version().feature());
        var dataSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    throw new AssertionError("SQL rendering must not access JDBC: " + method.getName());
                });
        var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(dataSource)
                .useDatabaseConfigure(configuration)
                .build();
        var query = new DefaultEasyEntityQuery(client);
        String value = "O'Reilly 中文";
        String sql = query.queryable(TestProduct.class)
                .where(p -> p.name().eq(value))
                .orderBy(p -> p.id().asc())
                .limit(0, 2)
                .toSQL();
        assertTrue(sql.contains(quote + "eqp_product" + quote), sql);
        assertTrue(sql.contains(quote + "name" + quote + " = ?"), sql);
        assertFalse(sql.contains(value), sql);
    }

    static Stream<Arguments> databases() {
        return Stream.of(
                Arguments.of(new MySQLDatabaseConfiguration(), "`"),
                Arguments.of(new PgSQLDatabaseConfiguration(), "\""),
                Arguments.of(new HanaDatabaseConfiguration(), "\""));
    }
}
