package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamRuntimeTest {
    @ParameterizedTest
    @MethodSource("databases")
    void generatedProxyRendersBoundSqlOnJava8(DatabaseConfiguration configuration, String quote) {
        assertEquals("1.8", System.getProperty("java.specification.version"));
        DefaultEasyEntityQuery query = TestClients.offline(configuration);
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
