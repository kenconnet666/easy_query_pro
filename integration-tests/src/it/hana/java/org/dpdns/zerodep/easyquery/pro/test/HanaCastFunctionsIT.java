package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class HanaCastFunctionsIT {
    private DefaultEasyEntityQuery query;

    @BeforeEach
    void createClient() throws SQLException {
        query = HanaTestDatabase.client();
    }

    @Test
    void signedNumbersAndDecimalsKeepTheirResultTypes() {
        assertEquals((byte) -128, source("-128").selectColumn(p -> p.name().toNumber(Byte.class)).toList().get(0));
        assertEquals(Short.MIN_VALUE, source("-32768").selectColumn(p -> p.name().toNumber(Short.class)).toList().get(0));
        assertEquals(Long.MAX_VALUE, source(Long.toString(Long.MAX_VALUE)).selectColumn(p -> p.name().toNumber(Long.class)).toList().get(0));
        assertEquals(1.25F, source("1.25").selectColumn(p -> p.name().toNumber(Float.class)).toList().get(0));
        assertEquals(1.25D, source("1.25").selectColumn(p -> p.name().toNumber(Double.class)).toList().get(0));
        assertEquals(0, new BigDecimal("123.45").compareTo(source("123.45")
                .selectColumn(p -> p.name().toNumber(BigDecimal.class)).toList().get(0)));
        assertNull(source(null).selectColumn(p -> p.name().toNumber(Integer.class)).toList().get(0));
        assertThrows(RuntimeException.class, () -> source("128").selectColumn(p -> p.name().toNumber(Byte.class)).toList());
        assertThrows(RuntimeException.class, () -> source("not a number").selectColumn(p -> p.name().toNumber(Integer.class)).toList());
    }

    @Test
    void stringConversionDoesNotTruncateLargeUnicodeValues() {
        String value = String.join("", Collections.nCopies(3000, "中文")) + "tail";
        assertEquals(value, source(value).selectColumn(p -> cast(p.name(), String.class)).toList().get(0));
        assertEquals("中", source("中文").selectColumn(p -> new StringTypeExpressionImpl<String>(
                p.getEntitySQLContext(), p.getTable(), null,
                fx -> fx.cast(args -> PropTypeColumn.columnFuncSelector(args, p.name()), char.class), String.class)).toList().get(0));
    }

    @Test
    void scalarCastSupportsTemporalBooleanAndUuidMappings() {
        // LOB 不等同于普通字符表达式，先显式转为字符值，再进行时间或布尔转换。
        assertEquals(LocalDate.of(2024, 2, 29), source("2024-02-29")
                .selectColumn(p -> cast(cast(p.name(), String.class), LocalDate.class)).toList().get(0));
        assertEquals(LocalTime.of(12, 34, 56), source("12:34:56")
                .selectColumn(p -> cast(cast(p.name(), String.class), LocalTime.class)).toList().get(0));
        assertEquals(Boolean.TRUE, source("true").selectColumn(p -> cast(cast(p.name(), String.class), Boolean.class)).toList().get(0));
        UUID uuid = UUID.fromString("A1960C70-62E7-4E4C-87A8-35939354A4DC");
        assertEquals(uuid, source(uuid.toString().toUpperCase(java.util.Locale.ROOT))
                .selectColumn(p -> cast(p.name(), UUID.class)).toList().get(0));
    }

    private static <T> AnyTypeExpressionImpl<T> cast(PropTypeColumn<?> column, Class<T> type) {
        return new AnyTypeExpressionImpl<>(column.getEntitySQLContext(), column.getTable(), null,
                fx -> fx.cast(args -> PropTypeColumn.columnFuncSelector(args, column), type), type);
    }

    private EntityQueryable<TestProductProxy, TestProduct> source(String text) {
        return query.queryable("SELECT 1 AS \"id\",TO_NCLOB(?) AS \"name\",CAST(1 AS DECIMAL(19,4)) AS \"amount\" FROM DUMMY",
                TestProduct.class, Arrays.asList(text));
    }
}
