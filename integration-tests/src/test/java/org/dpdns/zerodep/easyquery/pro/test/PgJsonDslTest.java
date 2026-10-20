package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.PgJsonRecord;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PgJsonDslTest {
    @Test
    void viewsAndConversionsUseDifferentContractsAndBoundValues() {
        var client = client();
        var query = client.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb())
                .setPath(List.of("a'\\{0}"), JsonInput.encoded("{\"value\":1}"), true).hasKey("a'\\{0}"));
        var sql = query.toSQLResult();
        assertTrue(sql.getSQL().contains("jsonb_set("), sql.getSQL());
        assertTrue(sql.getSQL().contains("?? CAST("), sql.getSQL());
        assertFalse(sql.getSQL().contains("a'"));
        assertEquals(List.of("a'\\{0}", "{\"value\":1}", true, "a'\\{0}"), sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(sql.getSQL(), query.cloneQueryable().toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.json(), PgSqlTypes.jsonb())));
        assertThrows(IllegalArgumentException.class, () -> client.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.plain(), PgSqlTypes.jsonb())));
        assertDoesNotThrow(() -> client.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).nativeCast(p.json(), PgSqlTypes.jsonb())).toSQL());
        assertDoesNotThrow(() -> client.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.json(), PgSqlTypes.json()).toJsonb()).toSQL());
    }

    @Test
    void nativeDialectAndInputKindAreExplicit() {
        var mysql = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        assertThrows(IllegalArgumentException.class, () -> mysql.queryable(TestProduct.class).where(PgSqlDsl::of));
        var pg = client();
        assertThrows(NullPointerException.class, () -> pg.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).value(null, PgSqlTypes.jsonb())));
        assertThrows(IllegalArgumentException.class, () -> pg.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).value(JsonInput.encoded("\uD83D"), PgSqlTypes.jsonb())));
    }

    private static DefaultEasyEntityQuery client() {
        var client = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        var handlers = client.getRuntimeContext().getJdbcTypeHandlerManager();
        var original = handlers.getHandler(String.class);
        PgSqlExtensions.configureMappings(client.getRuntimeContext().getQueryConfiguration());
        assertSame(original, handlers.getHandler(String.class));
        client.getRuntimeContext().getQueryConfiguration().applyValueConverter(new PgJsonRecord.PayloadConverter());
        return client;
    }
}
