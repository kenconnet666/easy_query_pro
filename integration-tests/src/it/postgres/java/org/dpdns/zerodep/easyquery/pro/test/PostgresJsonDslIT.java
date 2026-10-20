package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.enums.SQLExecuteStrategyEnum;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import com.mysql.cj.xdevapi.DbDoc;
import com.mysql.cj.xdevapi.JsonNumber;
import com.mysql.cj.xdevapi.JsonParser;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonPath;
import org.dpdns.zerodep.easyquery.pro.test.entity.PgJsonRecord;
import org.dpdns.zerodep.easyquery.pro.test.entity.JsonPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PostgresJsonDslIT {
    private PostgresTestDatabase.Schema schema;
    private DefaultEasyEntityQuery query;
    private JsonPayload.Converter converter;

    @BeforeEach
    void createFixture() throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        schema = PostgresTestDatabase.createSchema(source, "eqp_json_");
        try (var statement = schema.connection().createStatement()) {
            statement.execute("CREATE TABLE " + schema.qualified("eqp_pg_json") + " (id INTEGER PRIMARY KEY, json "
                    + PgSqlTypes.json().ddlType(SqlDialect.POSTGRESQL) + ", document " + PgSqlTypes.jsonb().ddlType(SqlDialect.POSTGRESQL)
                    + ", plain TEXT, payload JSONB)");
        }
        query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        PgSqlExtensions.configureMappings(query.getRuntimeContext().getQueryConfiguration());
        converter = new JsonPayload.Converter();
        query.getRuntimeContext().getQueryConfiguration().applyValueConverter(converter);
        var row = new PgJsonRecord();
        row.setId(1);
        row.setJson("{\"duplicate\":1,\"duplicate\":2}");
        row.setDocument("{\"tags\":[\"a\",\"b\"],\"nested\":{\"v\":1},\"empty\":null}");
        row.setPlain("ordinary text");
        row.setPayload(new JsonPayload("O'Reilly 中😀", 3));
        assertEquals(1L, query.insertable(row).executeRows());
    }

    @AfterEach
    void removeFixture() throws SQLException {
        if (schema != null) schema.close();
    }

    @Test
    void columnMappingAndExplicitJsonToJsonbConversionPreserveTheirRoles() {
        var row = query.queryable(PgJsonRecord.class).singleNotNull();
        assertEquals("{\"duplicate\":1,\"duplicate\":2}", row.getJson());
        assertEquals("ordinary text", row.getPlain());
        assertEquals(new JsonPayload("O'Reilly 中😀", 3), row.getPayload());
        assertEquals(1, converter.writes());
        String converted = query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.json(), PgSqlTypes.json()).toJsonb().asText()).singleNotNull();
        assertEquals(2, ((JsonNumber) JsonParser.parseDoc(converted).get("duplicate")).getInteger());
        assertEquals("null", query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).value(JsonInput.jsonNull(), PgSqlTypes.jsonb()).asText()).singleNotNull());
        assertNull(query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).value(JsonInput.sqlNull(), PgSqlTypes.jsonb()).asText()).toList().get(0));
        assertThrows(RuntimeException.class, () -> query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).value(JsonInput.encoded("invalid json"), PgSqlTypes.jsonb())).toList());
    }

    @Test
    void containmentKeysAndNativeJsonpathComposeInProjectionAndFiltering() {
        var selected = query.queryable(PgJsonRecord.class).select(p -> {
            var doc = PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb());
            return Select.DRAFT.of(doc.containsJson(JsonInput.encoded("{\"nested\":{\"v\":1}}")), doc.hasKey("empty"),
                    doc.hasAnyKey("absent", "empty"), doc.hasAllKeys("tags", "empty"), doc.hasAnyKey(), doc.hasAllKeys());
        });
        var row = selected.singleNotNull();
        assertTrue(row.getValue1());
        assertTrue(row.getValue2());
        assertTrue(row.getValue3());
        assertTrue(row.getValue4());
        assertFalse(row.getValue5());
        assertTrue(row.getValue6());
        assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        assertTrue(query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).value(JsonInput.encoded("[1,2]"), PgSqlTypes.jsonb())
                .containedBy(JsonInput.encoded("[1,2,3]"))).singleNotNull());
        assertTrue(query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb())
                .pathExists(new PgJsonPath("$.tags[*] ? (@ == \"a\")"))).singleNotNull());
        assertTrue(query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb())
                .pathExists(new PgJsonPath("$.nested.v ? (@ > $min)"), JsonInput.encoded("{\"min\":0}"), false)).singleNotNull());
        assertEquals(1L, query.queryable(PgJsonRecord.class).where(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb()).hasKey("empty").eq(true)).count());
    }

    @Test
    void nativeMutationsUseEncodedJsonAndKeepNullAndMissingParentRules() {
        assertEquals(1L, query.updatable(PgJsonRecord.class).setColumns(p -> p.document().set(PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb())
                .setPath(List.of("nested", "v"), JsonInput.encoded("2"), false)
                .setPath(List.of("new"), JsonInput.encoded("{\"x\":1}"), true).removeKey("empty"))).where(p -> p.id().eq(1)).executeRows());
        String saved = query.queryable(PgJsonRecord.class).singleNotNull().getDocument();
        var parsed = JsonParser.parseDoc(saved);
        assertEquals(2, ((JsonNumber) ((DbDoc) parsed.get("nested")).get("v")).getInteger());
        assertEquals(1, ((JsonNumber) ((DbDoc) parsed.get("new")).get("x")).getInteger());
        assertFalse(parsed.containsKey("empty"));
        assertEquals(saved, query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb())
                .setPath(List.of("missing", "child"), JsonInput.jsonNull(), true).asText()).singleNotNull());
        assertEquals("[\"a\"]", JsonParser.parseDoc(query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb())
                .removePath(List.of("tags", "-1")).asText()).singleNotNull()).get("tags").toString());
        assertNull(query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb())
                .setPath(List.of("new"), JsonInput.sqlNull(), true).asText()).toList().get(0));
        assertTrue(query.queryable(PgJsonRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb())
                .setPath(List.of("new"), JsonInput.jsonNull(), true).existsAt(JsonPath.root().key("new"))).singleNotNull());
    }

    @Test
    void mappedBusinessValuesSurviveSqlMutationAndRollbackWithoutDoubleSerialization() {
        try (var transaction = query.getEasyQueryClient().beginTransaction()) {
            assertEquals(1L, query.updatable(PgJsonRecord.class).setColumns(p -> p.payload().set(PgSqlDsl.of(p).view(p.payload(), PgSqlTypes.jsonb())
                    .setPath(List.of("name"), JsonInput.encoded("\"changed\""), true))).where(p -> p.id().eq(1)).executeRows());
            assertEquals(new JsonPayload("changed", 3), query.queryable(PgJsonRecord.class).singleNotNull().getPayload());
            assertEquals(1, converter.writes());
            transaction.rollback();
        }
        assertEquals(new JsonPayload("O'Reilly 中😀", 3), query.queryable(PgJsonRecord.class).singleNotNull().getPayload());
        var absent = new PgJsonRecord();
        absent.setId(2);
        absent.setDocument("null");
        query.insertable(absent).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows();
        var sqlNull = new PgJsonRecord();
        sqlNull.setId(3);
        query.insertable(sqlNull).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows();
        assertEquals(Arrays.asList(false, true, null), query.queryable(PgJsonRecord.class).orderBy(p -> p.id().asc())
                .selectColumn(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb()).containsJson(JsonInput.jsonNull())).toList());
    }

    @Test
    void nativeOperatorsRemainEligibleForGinIndexes() throws SQLException {
        var owner = schema.connection();
        try (var statement = owner.createStatement()) {
            statement.execute("CREATE INDEX eqp_json_gin ON " + schema.qualified("eqp_pg_json") + " USING GIN(document)");
        }
        var candidates = List.of(
                query.queryable(PgJsonRecord.class).where(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb()).hasKey("nested").eq(true)),
                query.queryable(PgJsonRecord.class).where(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb()).containsJson(JsonInput.encoded("{\"nested\":{\"v\":1}}")).eq(true)),
                query.queryable(PgJsonRecord.class).where(p -> PgSqlDsl.of(p).view(p.document(), PgSqlTypes.jsonb()).pathExists(new PgJsonPath("$.nested.v ? (@ == 1)")).eq(true)));
        owner.setSchema(schema.name());
        owner.setAutoCommit(false);
        try (var settings = owner.createStatement()) {
            // 这里只确认 operator 能匹配索引；一行 fixture 不作为性能或成本模型证据。
            settings.execute("SET LOCAL enable_seqscan = off");
            for (var candidate : candidates) {
                var rendered = candidate.toSQLResult();
                try (var explain = owner.prepareStatement("EXPLAIN (COSTS FALSE) " + rendered.getSQL())) {
                    var parameters = rendered.getSqlContext().getParameters();
                    for (int i = 0; i < parameters.size(); i++) explain.setObject(i + 1, parameters.get(i).getValue());
                    try (var result = explain.executeQuery()) {
                        var plan = new StringBuilder();
                        while (result.next()) plan.append(result.getString(1)).append('\n');
                        assertTrue(plan.toString().contains("eqp_json_gin"), plan.toString());
                    }
                }
            }
        } finally {
            owner.rollback();
            owner.setAutoCommit(true);
        }
    }

    @Test
    void computedBusinessValuesKeepTheirReaderInDraftAndScalarProjections() {
        var selected = query.queryable(PgJsonRecord.class).select(p -> Select.DRAFT.of(
                PgSqlDsl.of(p).view(p.payload(), PgSqlTypes.jsonb()).setPath(List.of("name"), JsonInput.encoded("\"projected\""), true), p.id()));
        var expected = new JsonPayload("projected", 3);
        assertEquals(expected, selected.singleNotNull().getValue1());
        assertEquals(expected, selected.cloneQueryable().singleNotNull().getValue1());
        assertEquals(new JsonPayload("O'Reilly 中😀", 3), query.queryable(PgJsonRecord.class)
                .selectColumn(p -> PgSqlDsl.of(p).view(p.payload(), PgSqlTypes.jsonb())).singleNotNull());
        assertEquals(1, converter.writes());
    }
}
