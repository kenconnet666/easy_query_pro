package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextDslTest {
    @Test
    void boundedTextUsesUtf16CapacityAndRejectsNonPortableEncodings() {
        var text = CommonTypes.unicodeText(3);
        assertEquals("😀a", text.prepareValue("😀a"));
        assertEquals("", text.prepareValue(""));
        assertNull(text.prepareValue(null));
        assertThrows(IllegalArgumentException.class, () -> text.prepareValue("😀ab"));
        assertThrows(IllegalArgumentException.class, () -> text.prepareValue("a\0"));
        assertThrows(IllegalArgumentException.class, () -> text.prepareValue("\uD83D"));
        assertThrows(IllegalArgumentException.class, () -> text.prepareValue("\uDE00"));
        assertThrows(IllegalArgumentException.class, () -> text.prepareValue("\uD83Da"));
        assertThrows(IllegalArgumentException.class, () -> CommonTypes.unicodeText(0));
        assertThrows(IllegalArgumentException.class, () -> CommonTypes.unicodeText(5001));
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void textExpressionsKeepStringsBoundAndUseExistingOperators(DecimalDslTest.Backend backend) {
        var type = CommonTypes.unicodeText(20);
        var query = backend.client().queryable(TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).value("O'Reilly 中😀{0}", type).toUpper().concat("!"));
        var rendered = query.toSQLResult();
        assertFalse(rendered.getSQL().contains("O'Reilly"));
        assertEquals(List.of("O'Reilly 中😀{0}", "!"), rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(rendered.getSQL(), query.cloneQueryable().toSQL());
        assertThrows(IllegalArgumentException.class, () -> backend.client().queryable(TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).value("😀", CommonTypes.unicodeText(1))).toSQL());
    }
}
