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

import static org.junit.jupiter.api.Assertions.*;

class HanaJsonTextDslIT {
    private HanaTestDatabase.TestSchema schema;
    private DefaultEasyEntityQuery query;
    private JsonPayload.Converter converter;
    private final String longValue = "中😀" + "x".repeat(6001);
    private final String largeDocument = "{\"long\":\"" + longValue + "\",\"active\":true,\"nothing\":null}";

    @BeforeEach
    void createFixture() throws SQLException {
        schema = HanaTestDatabase.createSchema("JSON_TEXT");
        try (var statement = schema.connection().createStatement()) {
            statement.execute(HanaJsonSupport.createDocumentFunctionSql(schema.name()));
            statement.execute("CREATE COLUMN TABLE " + schema.qualified("eqp_hana_json_text") + " (\"id\" INTEGER PRIMARY KEY, \"wide\" "
                    + HanaTypes.jsonNclob().ddlType(SqlDialect.HANA) + ", \"small\" " + HanaTypes.jsonNvarchar(1000).ddlType(SqlDialect.HANA) + ", \"payload\" NCLOB)");
        }
        query = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(new HanaOptions(10000, schema.name())), HanaExtensions::configure);
        converter = new JsonPayload.Converter();
        query.getRuntimeContext().getQueryConfiguration().applyValueConverter(converter);
        var row = new HanaJsonTextRecord();
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
        var selected = query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name()).select(p -> {
            var hana = HanaDsl.of(p);
            var large = hana.view(p.wide(), HanaTypes.jsonNclob());
            var small = hana.view(p.small(), HanaTypes.jsonNvarchar(1000));
            return Select.DRAFT.<String, Boolean, Boolean, Boolean, String, Boolean>of(large.textAt(JsonPath.root().key("long")), large.booleanAt(JsonPath.root().key("active")),
                    large.existsAt(JsonPath.root().key("nothing")), large.existsAt(JsonPath.root().key("absent")),
                    small.textAt(JsonPath.root().key("value")), small.booleanAt(JsonPath.root().key("active")));
        });
        var row = selected.singleNotNull();
        assertEquals(longValue, row.getValue1());
        assertTrue(row.getValue2());
        assertTrue(row.getValue3());
        assertFalse(row.getValue4());
        assertEquals("small", row.getValue5());
        assertFalse(row.getValue6());
        assertEquals(longValue, selected.cloneQueryable().singleNotNull().getValue1());
        var plainClient = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
        plainClient.getRuntimeContext().getQueryConfiguration().applyValueConverter(new JsonPayload.Converter());
        assertEquals(largeDocument, plainClient.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name())
                .selectColumn(p -> HanaDsl.of(p).view(p.wide(), HanaTypes.jsonNclob()).asText()).singleNotNull());
    }

    @Test
    void businessMappingsAndStorageConversionsRemainExplicit() {
        var selected = query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name()).select(p -> Select.DRAFT.of(
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
        var selected = query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name()).select(p -> {
            var hana = HanaDsl.of(p);
            var jsonNull = hana.value(JsonInput.jsonNull(), HanaTypes.jsonNclob());
            var sqlNull = hana.value(JsonInput.sqlNull(), HanaTypes.jsonNclob());
            return Select.DRAFT.of(jsonNull.asText(), sqlNull.asText(), jsonNull.existsAt(JsonPath.root()), sqlNull.existsAt(JsonPath.root()));
        }).singleNotNull();
        assertEquals("null", selected.getValue1());
        assertNull(selected.getValue2());
        assertTrue(selected.getValue3());
        assertFalse(selected.getValue4());
        assertThrows(RuntimeException.class, () -> query.queryable(HanaJsonTextRecord.class).asSchema(s -> schema.name())
                .selectColumn(p -> HanaDsl.of(p).value(JsonInput.encoded("not-json"), HanaTypes.jsonNclob()).existsAt(JsonPath.root())).toList());
    }
}
