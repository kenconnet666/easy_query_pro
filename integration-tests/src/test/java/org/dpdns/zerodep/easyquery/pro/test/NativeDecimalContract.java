package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.enums.SQLExecuteStrategyEnum;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NativeDecimalContract {
    private NativeDecimalContract() {
    }

    static void verify(DefaultEasyEntityQuery client, String table, NumericType<BigDecimal> type, List<BigDecimal> values) {
        var entities = new ArrayList<TestProduct>();
        for (int i = 0; i < values.size(); i++)
            entities.add(new TestProduct(i + 1, "number", type.prepareValue(values.get(i))));
        entities.add(new TestProduct(entities.size() + 1, "null", null));
        assertEquals(entities.size(), client.insertable(entities).asTable(table).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows());
        var rows = client.queryable(TestProduct.class).asTable(table).orderBy(p -> p.id().asc()).toList();
        for (int i = 0; i < values.size(); i++) equalValue(values.get(i), rows.get(i).getAmount());
        assertNull(rows.get(values.size()).getAmount());
        for (var value : values) {
            var selection = client.queryable(TestProduct.class).asTable(table).where(p -> p.id().eq(1)).select(p -> {
                var common = ProDsl.of(p);
                return Select.DRAFT.of(common.value(value, type), common.nativeCast(p.amount(), type), common.nullValue(type));
            });
            var result = selection.singleNotNull();
            equalValue(value, result.getValue1());
            equalValue(values.get(0), result.getValue2());
            assertNull(result.getValue3());
            equalValue(value, selection.cloneQueryable().singleNotNull().getValue1());
        }
        assertEquals(values.size(), client.queryable(TestProduct.class).asTable(table).where(p -> p.amount().isNotNull()).count());
    }

    static void nativeRounding(DefaultEasyEntityQuery client, String table, NumericType<BigDecimal> type, String input, String expected) {
        var result = client.queryable(TestProduct.class).asTable(table).where(p -> p.id().eq(1)).selectColumn(p -> {
            var common = ProDsl.of(p);
            return common.nativeCast(common.value(input, CommonTypes.unicodeText(100)), type);
        }).singleNotNull();
        equalValue(new BigDecimal(expected), result);
    }

    static void equalValue(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, expected.compareTo(actual), () -> "Expected " + expected + " but was " + actual);
    }
}
