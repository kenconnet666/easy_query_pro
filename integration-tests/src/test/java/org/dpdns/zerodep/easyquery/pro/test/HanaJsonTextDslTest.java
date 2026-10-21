package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaJsonTextRecord;
import org.dpdns.zerodep.easyquery.pro.test.entity.JsonPayload;
import org.junit.jupiter.api.Test;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.json.HanaJsonTextType;

import static org.junit.jupiter.api.Assertions.*;

class HanaJsonTextDslTest {
    @Test
    void fullTextConversionDoesNotRequireNavigationHelpersButPathsStillDo() {
        DefaultEasyEntityQuery query = TestClients.offline(new HanaDatabaseConfiguration(), HanaExtensions::configure);
        query.getRuntimeContext().getQueryConfiguration().applyValueConverter(new JsonPayload.Converter());
        assertDoesNotThrow(() -> query.queryable(HanaJsonTextRecord.class).selectColumn(p -> HanaDsl.of(p).view(p.wide(), HanaTypes.jsonNclob()).asText()).toSQL());
        assertThrows(IllegalStateException.class, () -> query.queryable(HanaJsonTextRecord.class).selectColumn(p -> HanaDsl.of(p).view(p.wide(), HanaTypes.jsonNclob()).existsAt(JsonPath.root())));
        assertThrows(IllegalArgumentException.class, () -> query.queryable(HanaJsonTextRecord.class).selectColumn(p -> HanaDsl.of(p).view(p.wide(), HanaTypes.jsonNclob())).distinct().toSQL());
        assertDoesNotThrow(() -> query.queryable(HanaJsonTextRecord.class).selectColumn(p -> HanaDsl.of(p).view(p.small(), HanaTypes.jsonNvarchar(1000))).distinct().toSQL());
        assertThrows(IllegalArgumentException.class, () -> query.queryable(HanaJsonTextRecord.class).selectColumn(p -> HanaDsl.of(p).view(p.small(), HanaTypes.jsonNvarchar(10))));
    }

    @Test
    void boundedJsonTextChecksUtf16CapacityAndDescriptorEquality() {
        HanaJsonTextType type = HanaTypes.jsonNvarchar(5);
        assertEquals(type, HanaTypes.jsonNvarchar(5));
        assertNotEquals(type, HanaTypes.jsonNclob());
        assertEquals("\"😀\"", type.prepareValue("\"😀\""));
        assertThrows(IllegalArgumentException.class, () -> type.prepareValue("\"😀ab\""));
        assertThrows(IllegalArgumentException.class, () -> HanaTypes.jsonNvarchar(5001));
        DefaultEasyEntityQuery query = TestClients.offline(new HanaDatabaseConfiguration(), HanaExtensions::configure);
        query.getRuntimeContext().getQueryConfiguration().applyValueConverter(new JsonPayload.Converter());
        assertDoesNotThrow(() -> query.queryable(HanaJsonTextRecord.class).selectColumn(p -> {
            HanaDsl hana = HanaDsl.of(p);
            return hana.view(hana.value(JsonInput.jsonNull(), type), HanaTypes.jsonNvarchar(5)).asText();
        }).toSQL());
    }
}
