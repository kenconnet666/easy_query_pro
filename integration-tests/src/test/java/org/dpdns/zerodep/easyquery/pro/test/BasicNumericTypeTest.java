package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BasicNumericTypeTest {
    static Stream<DecimalDslTest.Backend> backends() {
        return DecimalDslTest.backends();
    }

    @Test
    void finiteParametersKeepTheirJavaWidthAndNormalizeZero() {
        assertEquals(Long.MIN_VALUE, CommonTypes.bigint().prepareValue(Long.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, CommonTypes.bigint().prepareValue(Long.MAX_VALUE));
        assertEquals(Float.MIN_VALUE, CommonTypes.real().prepareValue(Float.MIN_VALUE));
        assertEquals(Double.MAX_VALUE, CommonTypes.doublePrecision().prepareValue(Double.MAX_VALUE));
        assertEquals(0, Float.floatToRawIntBits(CommonTypes.real().prepareValue(-0f)));
        assertEquals(0L, Double.doubleToRawLongBits(CommonTypes.doublePrecision().prepareValue(-0d)));
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> CommonTypes.real().prepareValue(value));
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> CommonTypes.doublePrecision().prepareValue(value));
        assertNull(CommonTypes.bigint().prepareValue(null));
        assertNull(CommonTypes.real().prepareValue(null));
        assertNull(CommonTypes.doublePrecision().prepareValue(null));
        assertEquals("FLOAT", CommonTypes.real().ddlType(SqlDialect.MYSQL));
        assertEquals("CAST({0} AS SIGNED)", CommonTypes.bigint().castTemplate(SqlDialect.MYSQL));
    }

    @ParameterizedTest
    @MethodSource("backends")
    void typedParametersUseNativeTargetsAndKeepNullAndClone(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var query = client.queryable(TestProduct.class).select(p -> {
            var dsl = ProDsl.of(p);
            return Select.DRAFT.of(dsl.value(Long.MAX_VALUE, CommonTypes.bigint()),
                    dsl.value(Float.MIN_VALUE, CommonTypes.real()),
                    dsl.value(Double.MAX_VALUE, CommonTypes.doublePrecision()),
                    dsl.nullValue(CommonTypes.real()));
        });
        var result = query.toSQLResult();
        assertEquals(Arrays.asList(Long.MAX_VALUE, Float.MIN_VALUE, Double.MAX_VALUE, null),
                result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        for (var type : new NumericType<?>[]{CommonTypes.bigint(), CommonTypes.real(), CommonTypes.doublePrecision()})
            assertTrue(result.getSQL().contains(type.castTemplate(backend.dialect()).replace("{0}", "?")), result.getSQL());
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).value(Double.POSITIVE_INFINITY, CommonTypes.doublePrecision())));
    }
}
