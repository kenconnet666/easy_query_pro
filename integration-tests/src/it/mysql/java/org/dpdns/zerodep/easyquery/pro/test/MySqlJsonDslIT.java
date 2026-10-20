package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.mysql.cj.xdevapi.JsonParser;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.MySqlJsonChange;
import org.dpdns.zerodep.easyquery.pro.test.entity.JsonPayload;
import org.dpdns.zerodep.easyquery.pro.test.entity.MySqlJsonRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MySqlJsonDslIT {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void nativeJsonContractWorksWithBothSqlModesAndPreparationModes(boolean serverPrepared, boolean noBackslashEscapes) throws SQLException {
        var source = MySqlTestDatabase.dataSource();
        source.setUseServerPrepStmts(serverPrepared);
        if (noBackslashEscapes) source.setSessionVariables("sql_mode='NO_BACKSLASH_ESCAPES'");
        String table = "eqp_mysql_json_" + UUID.randomUUID().toString().replace("-", "");
        try (var owner = source.getConnection()) {
            boolean created = false;
            try {
                try (var statement = owner.createStatement()) {
                    statement.execute("CREATE TABLE `" + table + "` (id INTEGER PRIMARY KEY, document "
                            + MySqlTypes.json().ddlType(SqlDialect.MYSQL) + ", plain TEXT, payload JSON) CHARACTER SET utf8mb4");
                    created = true;
                }
                var query = TestClients.configured(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
                MySqlExtensions.configureMappings(query.getRuntimeContext().getQueryConfiguration());
                var converter = new JsonPayload.Converter();
                query.getRuntimeContext().getQueryConfiguration().applyValueConverter(converter);
                var row = new MySqlJsonRecord();
                row.setId(1);
                row.setDocument("{\"tags\":[\"a\",\"b\",\"c\"],\"nested\":{\"v\":1},\"empty\":null}");
                row.setPlain("ordinary O'Reilly \\ 中😀");
                row.setPayload(new JsonPayload("O'Reilly \\ 中😀", 3));
                assertEquals(1L, query.insertable(row).asTable(table).executeRows());
                var read = query.queryable(MySqlJsonRecord.class).asTable(table).singleNotNull();
                assertEquals(row.getPlain(), read.getPlain());
                assertEquals(row.getPayload(), read.getPayload());
                assertEquals(1, converter.writes());
                verifyPredicates(query, table);
                verifyMutations(query, table);
                verifyBusinessMappingAndRollback(query, table, converter, row.getPayload());
                verifyNulls(query, table);
            } finally {
                if (created) try (var statement = owner.createStatement()) {
                    statement.execute("DROP TABLE `" + table + "`");
                }
            }
        }
    }

    private static void verifyPredicates(DefaultEasyEntityQuery query, String table) {
        var root = JsonPath.root();
        var selected = query.queryable(MySqlJsonRecord.class).asTable(table).select(p -> {
            var json = MySqlDsl.of(p).view(p.document(), MySqlTypes.json());
            return Select.DRAFT.of(json.containsJson(JsonInput.encoded("{\"nested\":{\"v\":1}}")),
                    json.containsJson(JsonInput.encoded("1"), root.key("nested").key("v")),
                    json.containsAnyPath(root.key("absent"), root.key("empty")),
                    json.containsAllPaths(root.key("nested"), root.key("empty")),
                    json.containsAllPaths(root.key("nested"), root.key("absent")),
                    json.containsJson(JsonInput.encoded("1"), root.key("absent")));
        });
        var result = selected.singleNotNull();
        assertTrue(result.getValue1());
        assertTrue(result.getValue2());
        assertTrue(result.getValue3());
        assertTrue(result.getValue4());
        assertFalse(result.getValue5());
        assertNull(result.getValue6());
        assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        assertEquals(1L, query.queryable(MySqlJsonRecord.class).asTable(table).where(p -> MySqlDsl.of(p).view(p.document(), MySqlTypes.json())
                .containsJson(JsonInput.encoded("\"a\""), root.key("tags")).eq(true)).count());
    }

    private static void verifyMutations(DefaultEasyEntityQuery query, String table) {
        var root = JsonPath.root();
        String key = "a'\\\"{0}中😀";
        assertEquals(1L, query.updatable(MySqlJsonRecord.class).asTable(table).setColumns(p -> p.document().set(MySqlDsl.of(p).view(p.document(), MySqlTypes.json())
                .setPaths(new MySqlJsonChange(root.key(key), JsonInput.encoded("{}")),
                        new MySqlJsonChange(root.key(key).key("child"), JsonInput.encoded("1")),
                        new MySqlJsonChange(root.key(key).key("child"), JsonInput.encoded("2")))
                .removePaths(root.key("tags").index(0), root.key("tags").index(0)))).where(p -> p.id().eq(1)).executeRows());
        var projection = query.queryable(MySqlJsonRecord.class).asTable(table).select(p -> {
            var json = MySqlDsl.of(p).view(p.document(), MySqlTypes.json());
            return Select.DRAFT.of(json.textAt(root.key(key).key("child")), json.at(root.key("tags")).asText(),
                    json.containsAnyPath(root.key(key)), json.containsJson(JsonInput.encoded("2"), root.key(key).key("child")));
        });
        var selected = projection.singleNotNull();
        assertEquals("2", selected.getValue1());
        assertEquals("[\"c\"]", selected.getValue2());
        assertTrue(selected.getValue3());
        assertTrue(selected.getValue4());
        var saved = query.queryable(MySqlJsonRecord.class).asTable(table).singleNotNull().getDocument();
        assertEquals(saved, query.queryable(MySqlJsonRecord.class).asTable(table).selectColumn(p -> MySqlDsl.of(p).view(p.document(), MySqlTypes.json())
                .setPath(root.key("missing").key("child"), JsonInput.jsonNull()).removePath(root.key("absent")).asText()).singleNotNull());
        String wrapped = query.queryable(MySqlJsonRecord.class).asTable(table).selectColumn(p -> MySqlDsl.of(p).value(JsonInput.encoded("1"), MySqlTypes.json())
                .setPath(root.index(9), JsonInput.encoded("2")).asText()).singleNotNull();
        assertEquals("[1, 2]", wrapped);
    }

    private static void verifyBusinessMappingAndRollback(DefaultEasyEntityQuery query, String table, JsonPayload.Converter converter, JsonPayload original) {
        try (var transaction = query.getEasyQueryClient().beginTransaction()) {
            assertEquals(1L, query.updatable(MySqlJsonRecord.class).asTable(table).setColumns(p -> p.payload().set(MySqlDsl.of(p).view(p.payload(), MySqlTypes.json())
                    .setPath(JsonPath.root().key("count"), JsonInput.encoded("4")))).where(p -> p.id().eq(1)).executeRows());
            assertEquals(new JsonPayload(original.name(), 4), query.queryable(MySqlJsonRecord.class).asTable(table).singleNotNull().getPayload());
            assertEquals(1, converter.writes());
            transaction.rollback();
        }
        assertEquals(original, query.queryable(MySqlJsonRecord.class).asTable(table).singleNotNull().getPayload());
    }

    private static void verifyNulls(DefaultEasyEntityQuery query, String table) {
        var selected = query.queryable(MySqlJsonRecord.class).asTable(table).select(p -> {
            var mysql = MySqlDsl.of(p);
            var sqlNull = mysql.value(JsonInput.sqlNull(), MySqlTypes.json());
            return Select.DRAFT.of(mysql.value(JsonInput.jsonNull(), MySqlTypes.json()).asText(), sqlNull.asText(),
                    sqlNull.containsJson(JsonInput.jsonNull()), sqlNull.containsAnyPath(JsonPath.root()),
                    sqlNull.setPath(JsonPath.root().key("x"), JsonInput.encoded("1")).asText(),
                    mysql.view(p.document(), MySqlTypes.json()).setPath(JsonPath.root(), JsonInput.sqlNull()).asText());
        }).singleNotNull();
        assertEquals("null", selected.getValue1());
        assertNull(selected.getValue2());
        assertNull(selected.getValue3());
        assertNull(selected.getValue4());
        assertNull(selected.getValue5());
        assertEquals("null", selected.getValue6());
        String changed = query.queryable(MySqlJsonRecord.class).asTable(table).selectColumn(p -> MySqlDsl.of(p).view(p.document(), MySqlTypes.json())
                .setPath(JsonPath.root().key("new"), JsonInput.sqlNull()).asText()).singleNotNull();
        assertEquals("null", JsonParser.parseDoc(changed).get("new").toString());
        assertThrows(RuntimeException.class, () -> query.queryable(MySqlJsonRecord.class).asTable(table)
                .selectColumn(p -> MySqlDsl.of(p).value(JsonInput.encoded("not json"), MySqlTypes.json())).toList());
    }
}
