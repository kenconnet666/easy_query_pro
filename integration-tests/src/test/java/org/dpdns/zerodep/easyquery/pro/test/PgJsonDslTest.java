package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonbColumnConverter;
import org.dpdns.zerodep.easyquery.pro.test.entity.PgJsonRecord;
import org.dpdns.zerodep.easyquery.pro.test.entity.JsonPayload;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import org.junit.jupiter.api.Test;

import com.easy.query.core.configuration.QueryConfiguration;
import com.easy.query.core.basic.extension.conversion.ColumnValueSQLConverter;
import com.easy.query.core.basic.api.select.Query;
import java.util.Arrays;
import com.easy.query.core.common.ToSQLResult;
import java.util.stream.Collectors;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;

import static org.junit.jupiter.api.Assertions.*;

class PgJsonDslTest {
    @Test
    void defaultMappingsPreserveRegisteredInstancesAndFillMissingTypes() {
        DefaultEasyEntityQuery query = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        QueryConfiguration configuration = query.getRuntimeContext().getQueryConfiguration();
        PgJsonbColumnConverter application = new PgJsonbColumnConverter();
        configuration.applyColumnValueSQLConverter(application);
        PgSqlExtensions.configureMappings(configuration);
        ColumnValueSQLConverter addedJson = configuration.getColumnValueSQLConverter(PgJsonColumnConverter.class);
        assertNotNull(addedJson);
        PgSqlExtensions.configureMappings(configuration);
        assertSame(application, configuration.getColumnValueSQLConverter(PgJsonbColumnConverter.class));
        assertSame(addedJson, configuration.getColumnValueSQLConverter(PgJsonColumnConverter.class));
    }

    @Test
    void viewsAndConversionsUseDifferentContractsAndBoundValues() {
        DefaultEasyEntityQuery client = client();
        Query<Boolean> query = client.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb())
                .setPath(Arrays.asList("a'\\{0}"), JsonInput.encoded("{\"value\":1}"), true).hasKey("a'\\{0}"));
        ToSQLResult sql = query.toSQLResult();
        assertTrue(sql.getSQL().contains("jsonb_set("), sql.getSQL());
        assertTrue(sql.getSQL().contains("?? CAST("), sql.getSQL());
        assertFalse(sql.getSQL().contains("a'"));
        assertEquals(Arrays.asList("a'\\{0}", "{\"value\":1}", true, "a'\\{0}"), sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(sql.getSQL(), query.cloneQueryable().toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.json(), PgSqlTypes.jsonb())));
        assertThrows(IllegalArgumentException.class, () -> client.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.plain(), PgSqlTypes.jsonb())));
        assertDoesNotThrow(() -> client.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).nativeCast(p.json(), PgSqlTypes.jsonb())).toSQL());
        assertDoesNotThrow(() -> client.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.json(), PgSqlTypes.json()).toJsonb()).toSQL());
    }

    @Test
    void nativeDialectAndInputKindAreExplicit() {
        DefaultEasyEntityQuery mysql = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        assertThrows(IllegalArgumentException.class, () -> mysql.queryable(TestProduct.class).where(PgSqlDsl::of));
        DefaultEasyEntityQuery pg = client();
        assertThrows(NullPointerException.class, () -> pg.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).value(null, PgSqlTypes.jsonb())));
        assertThrows(IllegalArgumentException.class, () -> pg.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).value(JsonInput.encoded("\uD83D"), PgSqlTypes.jsonb())));
    }

    private static DefaultEasyEntityQuery client() {
        DefaultEasyEntityQuery client = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        JdbcTypeHandlerManager handlers = client.getRuntimeContext().getJdbcTypeHandlerManager();
        JdbcTypeHandler original = handlers.getHandler(String.class);
        PgSqlExtensions.configureMappings(client.getRuntimeContext().getQueryConfiguration());
        assertSame(original, handlers.getHandler(String.class));
        client.getRuntimeContext().getQueryConfiguration().applyValueConverter(new JsonPayload.Converter());
        return client;
    }
}
