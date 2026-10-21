package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonFragmentExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.common.ToSQLResult;
import java.util.stream.Collectors;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathDslTest {
    static Stream<DecimalDslTest.Backend> backends() {
        return DecimalDslTest.backends().map(backend -> backend.dialect() == SqlDialect.HANA
                ? new DecimalDslTest.Backend(new HanaDatabaseConfiguration(new HanaOptions(5000, "APP{0}")), HanaExtensions::configure, SqlDialect.HANA)
                : backend);
    }

    @Test
    void pathsAreImmutableAndKeysCannotBecomeOperators() {
        JsonPath root = JsonPath.root();
        JsonPath path = root.key("a.b").index(0).key("q\"\\\n");
        assertEquals("$", root.toSqlJsonPath());
        assertEquals("$.\"a.b\"[0].\"q\\\"\\\\\\u000a\"", path.toSqlJsonPath());
        assertEquals(path, root.key("a.b").index(0).key("q\"\\\n"));
        assertNotEquals(root.key("0"), root.index(0));
        assertEquals("$.\"\"", root.key("").toSqlJsonPath());
        assertThrows(UnsupportedOperationException.class, () -> path.segments().clear());
        assertThrows(IllegalArgumentException.class, () -> root.index(-1));
        assertThrows(NullPointerException.class, () -> root.key(null));
        assertThrows(IllegalArgumentException.class, () -> root.key("a\0"));
        assertThrows(IllegalArgumentException.class, () -> root.key("\uD83D"));
        JsonPath bounded = root;
        for (int i = 0; i < 100; i++) bounded = bounded.index(0);
        JsonPath maximum = bounded;
        assertThrows(IllegalArgumentException.class, () -> maximum.key("too deep"));
    }

    @ParameterizedTest
    @MethodSource("backends")
    void literalKeysAndBindingsSurviveProjectionAndClone(DecimalDslTest.Backend backend) {
        String key = "O'Reilly\"\\{0}\n";
        JsonPath path = JsonPath.root().key("a.b").index(0).key(key);
        Query<String> query = backend.client().queryable(TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).jsonTextAt(p.name(), path));
        ToSQLResult rendered = query.toSQLResult();
        if (backend.dialect() == SqlDialect.HANA) {
            assertTrue(rendered.getSQL().contains("O''Reilly"), rendered.getSQL());
            assertTrue(rendered.getSQL().contains("\"APP{0}\".\"EQP_JSON_DOCUMENT\""), rendered.getSQL());
        } else {
            assertFalse(rendered.getSQL().contains("O'Reilly"));
        }
        assertEquals(rendered.getSQL().chars().filter(c -> c == '?').count(), rendered.getSqlContext().getParameters().size());
        ToSQLResult cloned = query.cloneQueryable().toSQLResult();
        assertEquals(rendered.getSQL(), cloned.getSQL());
        assertEquals(rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()),
                cloned.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
    }

    @ParameterizedTest
    @MethodSource("backends")
    void fragmentsRetainScopeAndDoNotPretendToBeComparableText(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        AtomicReference<JsonFragmentExpression> saved = new AtomicReference<JsonFragmentExpression>();
        client.queryable(TestProduct.class).where(p -> saved.set(ProDsl.of(p).jsonAt(p.name(), JsonPath.root())));
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p -> saved.get()).toSQL());
        assertThrows(IllegalArgumentException.class, () -> backend.client().queryable(TestProduct.class).selectColumn(p -> saved.get()).toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class).selectColumn(p -> {
            ProDsl sql = ProDsl.of(p);
            return sql.nullSafeEqualsValue(sql.jsonAt(p.name(), JsonPath.root()), "{}");
        }));
    }

    @Test
    void hanaMissingSupportConfigurationFailsBeforeJdbc() {
        DefaultEasyEntityQuery client = TestClients.offline(new HanaDatabaseConfiguration(), HanaExtensions::configure);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> client.queryable(TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).jsonExists(p.name(), JsonPath.root())));
        assertTrue(failure.getMessage().contains("jsonSupportSchema"), failure.getMessage());
    }
}
