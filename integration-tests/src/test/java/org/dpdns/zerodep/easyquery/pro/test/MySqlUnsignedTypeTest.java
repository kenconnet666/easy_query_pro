package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class MySqlUnsignedTypeTest {
    @Test
    void widthsUseJavaTypesThatRepresentTheirEntireUnsignedRange() {
        assertEquals((short) 255, MySqlTypes.unsignedTinyint().prepareValue((short) 255));
        assertEquals(65535, MySqlTypes.unsignedSmallint().prepareValue(65535));
        assertEquals(16777215, MySqlTypes.unsignedMediumint().prepareValue(16777215));
        assertEquals(4294967295L, MySqlTypes.unsignedInt().prepareValue(4294967295L));
        var maximum = new BigInteger("18446744073709551615");
        assertEquals(maximum, MySqlTypes.unsignedBigint().prepareValue(maximum));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedTinyint().prepareValue((short) 256));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedSmallint().prepareValue(65536));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedMediumint().prepareValue(16777216));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedInt().prepareValue(4294967296L));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedBigint().prepareValue(maximum.add(BigInteger.ONE)));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedBigint().prepareValue(BigInteger.valueOf(-1)));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedInt().ddlType(SqlDialect.POSTGRESQL));
        assertEquals("BIGINT UNSIGNED", MySqlTypes.unsignedBigint().ddlType(SqlDialect.MYSQL));
        assertEquals("CAST({0} AS UNSIGNED)", MySqlTypes.unsignedBigint().castTemplate(SqlDialect.MYSQL));
        assertNull(MySqlTypes.unsignedInt().prepareValue(null));
    }
}
