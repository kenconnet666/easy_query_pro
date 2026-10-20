package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeNumericDslTest {
    private record NativeType(SqlDialect dialect, NumericType<BigDecimal> type) { }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void nativeDescriptorsUseCommonOperatorsAndRejectOtherDialects(DecimalDslTest.Backend backend) {
        var types = List.of(new NativeType(SqlDialect.MYSQL, MySqlTypes.decimal(65, 30)),
                new NativeType(SqlDialect.POSTGRESQL, PgSqlTypes.numeric(2, -3)), new NativeType(SqlDialect.POSTGRESQL, PgSqlTypes.numeric()),
                new NativeType(SqlDialect.HANA, HanaTypes.decimal(38, 38)), new NativeType(SqlDialect.HANA, HanaTypes.smallDecimal()),
                new NativeType(SqlDialect.HANA, HanaTypes.floatingDecimal()));
        for (var entry : types) {
            var type = entry.type();
            if (entry.dialect() != backend.dialect()) {
                assertThrows(IllegalArgumentException.class, () -> backend.client().queryable(TestProduct.class)
                        .selectColumn(p -> ProDsl.of(p).nullValue(type)).toSQL());
                continue;
            }
            var query = backend.client().queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).value(BigDecimal.ZERO, type).add(BigDecimal.ONE));
            var result = query.toSQLResult();
            assertTrue(result.getSQL().contains("AS " + type.ddlType(backend.dialect())), result.getSQL());
            assertEquals(List.of(type.prepareValue(BigDecimal.ZERO), BigDecimal.ONE), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
            assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
        }
    }

    @Test
    void fixedAndUnconstrainedNumericValuesKeepExactCapacity() {
        var mysql = MySqlTypes.decimal(65, 30);
        fits(mysql, "9".repeat(35) + "." + "9".repeat(30));
        rejects(mysql, "1E35", "1E-31");
        var thousands = PgSqlTypes.numeric(2, -3);
        fits(thousands, "99000", "-99000", "1E3", "0.000");
        rejects(thousands, "100000", "99001", "999", "0.1");
        var fraction = PgSqlTypes.numeric(3, 5);
        fits(fraction, "0.00999", "-0.00999", "0.00001");
        rejects(fraction, "0.01", "1", "0.000001");
        fits(PgSqlTypes.numeric(1000, -1000), "9".repeat(1000) + "E1000");
        fits(PgSqlTypes.numeric(1000, 1000), "1E-1000");
        fits(PgSqlTypes.numeric(), "1E131071", "1E-16383", "9".repeat(1001));
        rejects(PgSqlTypes.numeric(), "1E131072", "1E-16384");
        fits(HanaTypes.decimal(38, 38), "0." + "9".repeat(38));
        rejects(HanaTypes.decimal(38, 38), "1", "1E-39");
        for (var type : List.of(mysql, thousands, fraction, PgSqlTypes.numeric(), HanaTypes.decimal(38, 38))) {
            assertNull(type.prepareValue(null));
            assertEquals(0, type.prepareValue(new BigDecimal(BigInteger.ZERO, Integer.MIN_VALUE)).signum());
            assertThrows(IllegalArgumentException.class, () -> type.prepareValue(new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE)));
            assertThrows(IllegalArgumentException.class, () -> type.prepareValue(new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE)));
        }
    }

    @Test
    void declarationRangesAreDatabaseSpecific() {
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.decimal(66, 0));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.decimal(38, 31));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.decimal(2, 3));
        assertThrows(IllegalArgumentException.class, () -> PgSqlTypes.numeric(1001, 0));
        assertThrows(IllegalArgumentException.class, () -> PgSqlTypes.numeric(0, 0));
        assertThrows(IllegalArgumentException.class, () -> PgSqlTypes.numeric(3, 1001));
        assertThrows(IllegalArgumentException.class, () -> PgSqlTypes.numeric(3, -1001));
        assertThrows(IllegalArgumentException.class, () -> HanaTypes.decimal(39, 0));
        assertThrows(IllegalArgumentException.class, () -> HanaTypes.decimal(2, 3));
        assertThrows(IllegalArgumentException.class, () -> HanaTypes.decimal(2, -1));
    }

    @Test
    void hanaDecimalFloatsRejectAnyRequiredRoundingOrUnderflow() {
        fits(HanaTypes.smallDecimal(), "1E-398", "9.999999999999999E-383", "1E384", "-9.999999999999999E384", "123.45000");
        rejects(HanaTypes.smallDecimal(), "1E-399", "9.999999999999999E-384", "1E385", "12345678901234567");
        fits(HanaTypes.floatingDecimal(), "1E-6176", "1E6144", "9.999999999999999999999999999999999E6144");
        rejects(HanaTypes.floatingDecimal(), "1E-6177", "1E6145", "1.2345678901234567890123456789012345");
        for (var type : List.of(HanaTypes.smallDecimal(), HanaTypes.floatingDecimal())) {
            assertNull(type.prepareValue(null));
            assertEquals(BigDecimal.ZERO, type.prepareValue(new BigDecimal(BigInteger.ZERO, Integer.MAX_VALUE)));
            assertThrows(IllegalArgumentException.class, () -> type.prepareValue(new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE)));
        }
    }

    @Test
    void hanaTinyintUsesShortWithoutChangingTheSignedByteContract() {
        var type = HanaTypes.tinyint();
        assertEquals(Short.class, type.javaType());
        assertEquals((short) 255, type.prepareValue((short) 255));
        assertEquals((short) 0, type.prepareValue((short) 0));
        assertNull(type.prepareValue(null));
        assertThrows(IllegalArgumentException.class, () -> type.prepareValue((short) -1));
        assertThrows(IllegalArgumentException.class, () -> type.prepareValue((short) 256));
        assertThrows(IllegalArgumentException.class, () -> type.ddlType(SqlDialect.MYSQL));
    }

    private static void fits(NumericType<BigDecimal> type, String... values) {
        for (String value : values)
            assertEquals(0, new BigDecimal(value).compareTo(type.prepareValue(new BigDecimal(value))), value);
    }

    private static void rejects(NumericType<BigDecimal> type, String... values) {
        for (String value : values)
            assertThrows(IllegalArgumentException.class, () -> type.prepareValue(new BigDecimal(value)), value);
    }
}
