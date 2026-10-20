package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PgArrayTypeTest {
    @Test
    void arraysSnapshotValuesPreserveNullsAndKeepTheirSqlElementType() {
        var type = PgSqlTypes.array(Integer.class);
        var input = new ArrayList<>(Arrays.asList(1, null, 2));
        var value = type.value(input);
        input.set(0, 99);
        assertEquals(Arrays.asList(1, null, 2), value.elements());
        assertThrows(UnsupportedOperationException.class, () -> value.elements().add(3));
        assertEquals(value, type.value(Arrays.asList(1, null, 2)));
        assertNotEquals(value, PgSqlTypes.array(Long.class).value(Arrays.asList(1L, null, 2L)));
        assertEquals("int4[]", type.ddlType(SqlDialect.POSTGRESQL));
        assertNull(type.value(null));
        assertThrows(IllegalArgumentException.class, () -> type.ddlType(SqlDialect.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> PgSqlTypes.array(byte[].class));
        assertThrows(IllegalArgumentException.class, () -> PgSqlTypes.array(String.class).value(Arrays.asList("bad\u0000")));
    }
}
