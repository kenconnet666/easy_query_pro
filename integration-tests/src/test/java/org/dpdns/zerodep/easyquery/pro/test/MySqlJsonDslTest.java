package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.MySqlJsonChange;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.MySqlJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.JsonPayload;
import org.dpdns.zerodep.easyquery.pro.test.entity.MySqlJsonRecord;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MySqlJsonDslTest {
    @Test
    void defaultMappingsPreserveRegisteredInstances() {
        var query = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        var configuration = query.getRuntimeContext().getQueryConfiguration();
        var application = new MySqlJsonColumnConverter();
        configuration.applyColumnValueSQLConverter(application);
        MySqlExtensions.configureMappings(configuration);
        MySqlExtensions.configureMappings(configuration);
        assertSame(application, configuration.getColumnValueSQLConverter(MySqlJsonColumnConverter.class));
    }

    @Test
    void nativeChangesBindDataInOrderAndSnapshotCallerArrays() {
        var key = JsonPath.root().key("a'\\{0}中😀");
        var changes = new MySqlJsonChange[]{new MySqlJsonChange(key, JsonInput.encoded("1")), new MySqlJsonChange(key, JsonInput.encoded("2"))};
        var paths = new JsonPath[]{key};
        var selected = client().queryable(MySqlJsonRecord.class).selectColumn(p -> MySqlDsl.of(p).view(p.document(), MySqlTypes.json())
                .setPaths(changes).containsAllPaths(paths));
        changes[0] = new MySqlJsonChange(JsonPath.root(), JsonInput.jsonNull());
        paths[0] = JsonPath.root();
        var rendered = selected.toSQLResult();
        assertFalse(rendered.getSQL().contains("a'"));
        assertTrue(rendered.getSQL().contains("JSON_CONTAINS_PATH(JSON_SET("), rendered.getSQL());
        assertEquals(List.of(key.toSqlJsonPath(), "1", key.toSqlJsonPath(), "2", key.toSqlJsonPath()),
                rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(rendered.getSQL(), selected.cloneQueryable().toSQL());
    }

    @Test
    void invalidViewsAndMutationShapesFailBeforeJdbc() {
        var query = client();
        assertThrows(IllegalArgumentException.class, () -> query.queryable(MySqlJsonRecord.class).selectColumn(p -> MySqlDsl.of(p).view(p.plain(), MySqlTypes.json())));
        assertDoesNotThrow(() -> query.queryable(MySqlJsonRecord.class).selectColumn(p -> MySqlDsl.of(p).nativeCast(p.plain(), MySqlTypes.json())).toSQL());
        query.queryable(MySqlJsonRecord.class).where(p -> {
            var json = MySqlDsl.of(p).view(p.document(), MySqlTypes.json());
            assertThrows(IllegalArgumentException.class, json::setPaths);
            assertThrows(IllegalArgumentException.class, json::removePaths);
            assertThrows(IllegalArgumentException.class, json::containsAnyPath);
            assertThrows(IllegalArgumentException.class, json::containsAllPaths);
            assertThrows(IllegalArgumentException.class, () -> json.removePath(JsonPath.root()));
            assertThrows(NullPointerException.class, () -> json.containsJson(null));
            assertThrows(IllegalArgumentException.class, () -> json.setPath(JsonPath.root(), JsonInput.encoded("\uD83D")));
        });
        var pg = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        assertThrows(IllegalArgumentException.class, () -> pg.queryable(TestProduct.class).where(MySqlDsl::of));
    }

    private static DefaultEasyEntityQuery client() {
        var query = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        var configuration = query.getRuntimeContext().getQueryConfiguration();
        MySqlExtensions.configureMappings(configuration);
        configuration.applyValueConverter(new JsonPayload.Converter());
        return query;
    }
}
