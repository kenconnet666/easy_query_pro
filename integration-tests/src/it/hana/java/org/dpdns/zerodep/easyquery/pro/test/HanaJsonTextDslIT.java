package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.hana.json.HanaJsonSupport;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaJsonTextRecord;
import org.dpdns.zerodep.easyquery.pro.test.entity.JsonPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Collections;
import java.sql.Statement;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft6Proxy;
import com.easy.query.core.proxy.core.draft.Draft6;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.json.HanaJsonTextExpression;
import com.easy.query.core.proxy.core.draft.proxy.Draft2Proxy;
import com.easy.query.core.proxy.core.draft.Draft2;
import com.easy.query.core.proxy.core.draft.Draft4;

import static org.junit.jupiter.api.Assertions.*;

class HanaJsonTextDslIT {
    private HanaTestDatabase.TestSchema schema;
    private DefaultEasyEntityQuery query;
    private JsonPayload.Converter converter;
    private final String longValue = "中😀" + String.join("", Collections.nCopies(6001, "x"));
    private final String largeDocument = "{\"long\":\"" + longValue + "\",\"active\":true,\"nothing\":null}";

    @BeforeEach
    void createFixture() throws SQLException {
        schema = HanaTestDatabase.createSchema("JSON_TEXT");
        try (Statement statement = schema.connection().createStatement()) {
            statement.execute(HanaJsonSupport.createDocumentFunctionSql(schema.name()));
            statement.execute("CREATE COLUMN TABLE " + schema.qualified("eqp_hana_json_text") + " (\"id\" INTEGER PRIMARY KEY, \"wide\" "
                    + HanaTypes.jsonNclob().ddlType(SqlDialect.HANA) + ", \"small\" " + HanaTypes.jsonNvarchar(1000).ddlType(SqlDialect.HANA) + ", \"payload\" NCLOB)");
        }
        query = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(new HanaOptions(10000, schema.name())), HanaExtensions::configure);
        converter = new JsonPayload.Converter();
        query.getRuntimeContext().getQueryConfiguration().applyValueConverter(converter);
        HanaJsonTextRecord row = new HanaJsonTextRecord();
        row.setId(1);
        row.setWide(largeDocument);
        row.setSmall("{\"value\":\"small\",\"active\":false,\"nothing\":null}");
        row.setPayload(new JsonPayload("business 中😀", 3));
        assertEquals(1L, query.insertable(row).asSchema(s -> schema.name()).executeRows());
    }

    @AfterEach
    void removeFixture() throws SQLException {
        if (schema != null) schema.close();
    }

    @Test
    void unicodeTextStorageUsesPortableReadersWithoutTruncatingWholeValues() throws SQLException {
        EntityQueryable<Draft6Proxy<String, Boolean, Boolean, Boolean, String, Boolean>, Draft6<String, Boolean, Boolean, Boolean, String, Boolean>> selected = query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name()).select(p -> {
            HanaDsl hana = HanaDsl.of(p);
            HanaJsonTextExpression<String> large = hana.view(p.wide(), HanaTypes.jsonNclob());
            HanaJsonTextExpression<String> small = hana.view(p.small(), HanaTypes.jsonNvarchar(1000));
            return Select.DRAFT.<String, Boolean, Boolean, Boolean, String, Boolean>of(large.textAt(JsonPath.root().key("long")), large.booleanAt(JsonPath.root().key("active")),
                    large.existsAt(JsonPath.root().key("nothing")), large.existsAt(JsonPath.root().key("absent")),
                    small.textAt(JsonPath.root().key("value")), small.booleanAt(JsonPath.root().key("active")));
        });
        Draft6<String, Boolean, Boolean, Boolean, String, Boolean> row = selected.singleNotNull();
        assertEquals(longValue, row.getValue1());
        assertTrue(row.getValue2());
        assertTrue(row.getValue3());
        assertFalse(row.getValue4());
        assertEquals("small", row.getValue5());
        assertFalse(row.getValue6());
        assertEquals(longValue, selected.cloneQueryable().singleNotNull().getValue1());
        DefaultEasyEntityQuery plainClient = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
        plainClient.getRuntimeContext().getQueryConfiguration().applyValueConverter(new JsonPayload.Converter());
        assertEquals(largeDocument, plainClient.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name())
                .selectColumn(p -> HanaDsl.of(p).view(p.wide(), HanaTypes.jsonNclob()).asText()).singleNotNull());
    }

    @Test
    void businessMappingsAndStorageConversionsRemainExplicit() {
        EntityQueryable<Draft2Proxy<JsonPayload, Integer>, Draft2<JsonPayload, Integer>> selected = query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name()).select(p -> Select.DRAFT.of(
                HanaDsl.of(p).view(p.payload(), HanaTypes.jsonNclob()), p.id()));
        assertEquals(new JsonPayload("business 中😀", 3), selected.singleNotNull().getValue1());
        assertEquals(new JsonPayload("business 中😀", 3), query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name())
                .selectColumn(p -> HanaDsl.of(p).view(p.payload(), HanaTypes.jsonNclob())).singleNotNull());
        assertEquals(1, converter.writes());
        assertEquals("small", query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name()).selectColumn(p ->
                HanaDsl.of(p).nativeCast(p.small(), HanaTypes.jsonNclob()).textAt(JsonPath.root().key("value"))).singleNotNull());
        assertEquals(1, query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name()).selectColumn(p ->
                HanaDsl.of(p).view(p.small(), HanaTypes.jsonNvarchar(1000))).distinct().toList().size());
    }

    @Test
    void jsonNullSqlNullAndMalformedTextKeepSeparateMeanings() {
        Draft4<String, String, Boolean, Boolean> selected = query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name()).select(p -> {
            HanaDsl hana = HanaDsl.of(p);
            HanaJsonTextExpression<String> jsonNull = hana.value(JsonInput.jsonNull(), HanaTypes.jsonNclob());
            HanaJsonTextExpression<String> sqlNull = hana.value(JsonInput.sqlNull(), HanaTypes.jsonNclob());
            return Select.DRAFT.of(jsonNull.asText(), sqlNull.asText(), jsonNull.existsAt(JsonPath.root()), sqlNull.existsAt(JsonPath.root()));
        }).singleNotNull();
        assertEquals("null", selected.getValue1());
        assertNull(selected.getValue2());
        assertTrue(selected.getValue3());
        assertFalse(selected.getValue4());
        assertThrows(RuntimeException.class, () -> query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name())
                .selectColumn(p -> HanaDsl.of(p).value(JsonInput.encoded("not-json"), HanaTypes.jsonNclob()).existsAt(JsonPath.root())).toList());
    }

    @Test
    void derivedCteJoinsKeepBusinessReadersSeparateFromText() {
        EntityQueryable<Draft2Proxy<Integer, JsonPayload>, Draft2<Integer, JsonPayload>> payloads = query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name()).select(p -> Select.DRAFT.of(p.id(),
                HanaDsl.of(p).view(p.payload(), HanaTypes.jsonNclob()))).toCteAs("eqp_payload_origin");
        EntityQueryable<Draft2Proxy<Integer, String>, Draft2<Integer, String>> documents = query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name()).select(p -> Select.DRAFT.of(p.id(),
                HanaDsl.of(p).view(p.wide(), HanaTypes.jsonNclob()))).toCteAs("eqp_document_origin");
        EntityQueryable<Draft2Proxy<JsonPayload, String>, Draft2<JsonPayload, String>> joined = payloads.cloneQueryable().innerJoin(documents, (left, right) -> left.value1().eq(right.value1())).select((left, right) -> Select.DRAFT.of(
                HanaDsl.of(left).view(left.value2(), HanaTypes.jsonNclob()),
                HanaDsl.of(right).view(right.value2(), HanaTypes.jsonNclob()).asText()));
        assertEquals(new JsonPayload("business 中😀", 3), joined.singleNotNull().getValue1());
        assertEquals(largeDocument, joined.cloneQueryable().singleNotNull().getValue2());
        EntityQueryable<Draft2Proxy<String, Integer>, Draft2<String, Integer>> reordered = payloads.cloneQueryable().select(p -> Select.DRAFT.of(HanaDsl.of(p).view(p.value2(), HanaTypes.jsonNclob()).asText(), p.value1()));
        assertEquals(1, reordered.singleNotNull().getValue2());
        assertEquals(new JsonPayload("business 中😀", 3), payloads.singleNotNull().getValue2());
        assertEquals(1, converter.writes());
    }
}
