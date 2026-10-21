package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HanaConfigurationTest {
    @Test
    void quotesIdentifiersWithoutFoldingCaseOrInterpretingSql() {
        HanaSQLKeyword keyword = new HanaSQLKeyword();
        assertEquals("\"Mixed中文\"", keyword.getQuoteName("Mixed中文"));
        assertEquals("\"order\"", keyword.getQuoteName("order"));
        assertEquals("\"a\"\"b\"", keyword.getQuoteName("a\"b"));
        assertEquals("\"a\"\"b\"", keyword.getQuoteName("\"a\"\"b\""));
        assertEquals("a\"b", keyword.unquoteName("\"a\"\"b\""));
        assertEquals("\"\"\"name\"\"\"", keyword.quoteUnquotedName("\"name\""));
        assertEquals("\"name); DROP TABLE x;--\"", keyword.getQuoteName("name); DROP TABLE x;--"));
        assertThrows(IllegalArgumentException.class, () -> keyword.getQuoteName("\"a\"b\""));
        assertThrows(IllegalArgumentException.class, () -> keyword.getQuoteName("\"\""));
        assertThrows(IllegalArgumentException.class, () -> keyword.getQuoteName("\0"));
    }

    @Test
    void comparesNumericRevisionComponentsAndRejectsUnrecognizedVersions() {
        HanaVersion version = HanaVersion.parse("2.00.088.00.1760424921");
        assertEquals(new HanaVersion(2, 0, 88), version);
        assertTrue(version.compareTo(HanaVersion.parse("2.00.9")) > 0);
        assertTrue(version.compareTo(HanaVersion.parse("2.00.100")) < 0);
        assertThrows(IllegalArgumentException.class, () -> HanaVersion.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> new HanaVersion(2, 0, -1));
    }
}
