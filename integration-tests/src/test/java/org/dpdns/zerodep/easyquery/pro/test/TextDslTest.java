package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import org.dpdns.zerodep.easyquery.pro.dsl.type.TextType;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.common.ToSQLResult;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TextDslTest {
    @Test
    void boundedTextUsesUtf16CapacityAndRejectsNonPortableEncodings() {
        TextType text = CommonTypes.unicodeText(3);
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
        TextType type = CommonTypes.unicodeText(20);
        Query<String> query = backend.client().queryable(TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).value("O'Reilly 中😀{0}", type).toUpper().concat("!"));
        ToSQLResult rendered = query.toSQLResult();
        assertFalse(rendered.getSQL().contains("O'Reilly"));
        assertEquals(Arrays.asList("O'Reilly 中😀{0}", "!"), rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(rendered.getSQL(), query.cloneQueryable().toSQL());
        assertThrows(IllegalArgumentException.class, () -> backend.client().queryable(TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).value("😀", CommonTypes.unicodeText(1))).toSQL());
    }
}
