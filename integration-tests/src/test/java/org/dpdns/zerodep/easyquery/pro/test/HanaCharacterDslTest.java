package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import org.dpdns.zerodep.easyquery.pro.dsl.type.TextType;
import java.util.Arrays;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.api.select.Query;

import static org.junit.jupiter.api.Assertions.*;

class HanaCharacterDslTest {
    @Test
    void characterParametersPreserveExactContentAndMakeAlphanumPaddingExplicit() {
        TextType ascii = HanaTypes.varchar(6);
        assertEquals("O'R !", ascii.prepareValue("O'R !"));
        assertThrows(IllegalArgumentException.class, () -> ascii.prepareValue("中"));
        TextType unicode = HanaTypes.nvarchar(3);
        assertEquals("中😀", unicode.prepareValue("中😀"));
        assertThrows(IllegalArgumentException.class, () -> unicode.prepareValue("😀😀"));
        TextType alpha = HanaTypes.alphanum(6);
        for (String text : Arrays.asList("", "000012", "0a", "12 ", " 12", "-1", "中😀"))
            assertEquals(text, alpha.prepareValue(text));
        for (String text : Arrays.asList("12", "0012", "0", "1234567"))
            assertThrows(IllegalArgumentException.class, () -> alpha.prepareValue(text));
        for (TextType type : Arrays.asList(ascii, unicode, alpha)) {
            assertNull(type.prepareValue(null));
            assertThrows(IllegalArgumentException.class, () -> type.prepareValue("\0"));
            assertThrows(IllegalArgumentException.class, () -> type.prepareValue("\ud800"));
            assertThrows(IllegalArgumentException.class, () -> type.ddlType(SqlDialect.POSTGRESQL));
        }
        assertThrows(IllegalArgumentException.class, () -> HanaTypes.alphanum(128));
        assertThrows(IllegalArgumentException.class, () -> HanaTypes.nvarchar(5001));
        assertThrows(IllegalArgumentException.class, () -> HanaTypes.varchar(0));
        assertThrows(IllegalArgumentException.class, () -> HanaTypes.shortText(5001));
    }

    @Test
    void nativeCharacterConversionsUseCommonStringFunctionsAndClone() {
        DefaultEasyEntityQuery client = TestClients.offline(new HanaDatabaseConfiguration(), HanaExtensions::configure);
        Query<String> rows = client.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).nativeCast(p.name(), HanaTypes.alphanum(6)).concat("!"));
        assertTrue(rows.toSQL().contains("AS ALPHANUM(6)"), rows.toSQL());
        assertEquals(rows.toSQL(), rows.cloneQueryable().toSQL());
        assertEquals("SHORTTEXT(20)", HanaTypes.shortText(20).ddlType(SqlDialect.HANA));
    }
}
