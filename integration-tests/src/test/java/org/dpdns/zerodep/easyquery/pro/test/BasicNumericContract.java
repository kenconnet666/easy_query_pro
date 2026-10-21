package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.enums.SQLExecuteStrategyEnum;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.BasicNumericRecord;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class BasicNumericContract {
    private BasicNumericContract() {
    }

    static String columns(DefaultEasyEntityQuery client, SqlDialect dialect) {
        var names = client.getRuntimeContext().getQueryConfiguration().getDialect();
        return names.getQuoteName("id") + " INTEGER PRIMARY KEY, "
                + names.getQuoteName("long_value") + " " + CommonTypes.bigint().ddlType(dialect) + ", "
                + names.getQuoteName("real_value") + " " + CommonTypes.real().ddlType(dialect) + ", "
                + names.getQuoteName("double_value") + " " + CommonTypes.doublePrecision().ddlType(dialect);
    }

    static void verify(DefaultEasyEntityQuery client, String table) {
        verify(client, table, true);
    }

    static void verifyMySqlTextResults(DefaultEasyEntityQuery client, String table) {
        verify(client, table, false);
    }

    private static void verify(DefaultEasyEntityQuery client, String table, boolean exactRealResults) {
        var values = List.of(
                new BasicNumericRecord(1, Long.MIN_VALUE, Float.MAX_VALUE, Double.MAX_VALUE),
                new BasicNumericRecord(2, Long.MAX_VALUE, -Float.MAX_VALUE, -Double.MAX_VALUE),
                new BasicNumericRecord(3, 9007199254740993L, Float.MIN_NORMAL, Double.MIN_NORMAL),
                new BasicNumericRecord(4, -9007199254740993L, -Float.MIN_NORMAL, -Double.MIN_NORMAL),
                new BasicNumericRecord(5, 1L, Float.MIN_VALUE, Double.MIN_VALUE),
                new BasicNumericRecord(6, -1L, -Float.MIN_VALUE, -Double.MIN_VALUE),
                new BasicNumericRecord(7, 0L, -0f, -0d),
                new BasicNumericRecord(8, 42L, 0.1f, 0.1d),
                new BasicNumericRecord(9, null, null, null));
        // 普通实体 setter 不自动应用描述校验；显式准备值后再验物理存储的共同契约。
        var stored = values.stream().map(row -> new BasicNumericRecord(row.getId(),
                CommonTypes.bigint().prepareValue(row.getLongValue()),
                CommonTypes.real().prepareValue(row.getRealValue()),
                CommonTypes.doublePrecision().prepareValue(row.getDoubleValue()))).toList();
        assertEquals(stored.size(), client.insertable(stored).asTable(table)
                .setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows());
        var loaded = client.queryable(BasicNumericRecord.class).asTable(table).orderBy(p -> p.id().asc()).toList();
        assertEquals(stored.size(), loaded.size());
        for (int i = 0; i < stored.size(); i++) same(stored.get(i), loaded.get(i), exactRealResults);
        if (!exactRealResults) {
            // 文本协议已经损失有效位，getter 不可能恢复；保留明确的原生负证据。
            assertEquals(3.40282E38f, loaded.get(0).getRealValue());
            assertNotEquals(Float.MAX_VALUE, loaded.get(0).getRealValue());
        }

        for (var row : values) {
            var parameter = client.queryable(BasicNumericRecord.class).asTable(table).where(p -> p.id().eq(1)).select(p -> {
                var dsl = ProDsl.of(p);
                return Select.DRAFT.of(dsl.value(row.getLongValue(), CommonTypes.bigint()),
                        dsl.value(row.getRealValue(), CommonTypes.real()),
                        dsl.value(row.getDoubleValue(), CommonTypes.doublePrecision()),
                        dsl.nativeCast(dsl.value(row.getRealValue(), CommonTypes.real()), CommonTypes.doublePrecision()));
            }).singleNotNull();
            assertEquals(CommonTypes.bigint().prepareValue(row.getLongValue()), parameter.getValue1());
            var real = CommonTypes.real().prepareValue(row.getRealValue());
            if (exactRealResults || real == null) assertEquals(real, parameter.getValue2());
            assertEquals(CommonTypes.doublePrecision().prepareValue(row.getDoubleValue()), parameter.getValue3());
            assertEquals(real == null ? null : real.doubleValue(), parameter.getValue4());
        }
        var converted = client.queryable(BasicNumericRecord.class).asTable(table).select(p -> {
            var dsl = ProDsl.of(p);
            return Select.DRAFT.of(p.id(), dsl.nativeCast(p.longValue(), CommonTypes.bigint()),
                    dsl.nativeCast(p.realValue(), CommonTypes.real()),
                    dsl.nativeCast(p.doubleValue(), CommonTypes.doublePrecision()),
                    dsl.nativeCast(p.realValue(), CommonTypes.doublePrecision()));
        }).toCteAs("converted_numbers").orderBy(p -> p.value1().asc());
        var drafts = converted.cloneQueryable().toList();
        assertEquals(stored.size(), drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            var actual = drafts.get(i);
            var expected = stored.get(i);
            same(expected, new BasicNumericRecord(actual.getValue1(), actual.getValue2(), actual.getValue3(), actual.getValue4()), exactRealResults);
            assertEquals(expected.getRealValue() == null ? null : expected.getRealValue().doubleValue(), actual.getValue5());
        }
        assertEquals(9L, converted.cloneQueryable().count());
        var page = converted.cloneQueryable().toPageResult(2, 3);
        assertEquals(9L, page.getTotal());
        assertEquals(List.of(4, 5, 6), page.getData().stream().map(row -> row.getValue1()).toList());
        assertEquals(Long.MAX_VALUE, client.queryable(BasicNumericRecord.class).asTable(table).where(p -> p.id().eq(2))
                .selectColumn(p -> ProDsl.of(p).nativeCast(p.longValue(), CommonTypes.bigint())).singleNotNull());
        if (exactRealResults) {
            assertEquals(Float.MAX_VALUE, client.queryable(BasicNumericRecord.class).asTable(table).where(p -> p.id().eq(1))
                    .selectColumn(p -> ProDsl.of(p).nativeCast(p.realValue(), CommonTypes.real())).singleNotNull());
        }
        assertEquals((double) Float.MAX_VALUE, client.queryable(BasicNumericRecord.class).asTable(table).where(p -> p.id().eq(1))
                .selectColumn(p -> ProDsl.of(p).nativeCast(p.realValue(), CommonTypes.doublePrecision())).singleNotNull());
        assertEquals(0.1d, client.queryable(BasicNumericRecord.class).asTable(table).where(p -> p.id().eq(8))
                .selectColumn(p -> ProDsl.of(p).nativeCast(p.doubleValue(), CommonTypes.doublePrecision())).singleNotNull());
    }

    private static void same(BasicNumericRecord expected, BasicNumericRecord actual, boolean exactRealResults) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getLongValue(), actual.getLongValue());
        // Float/Double.equals 比较实际位模式；不以误差容限掩盖极值下溢、零符号或精度变化。
        if (exactRealResults || expected.getRealValue() == null)
            assertEquals(expected.getRealValue(), actual.getRealValue(), "REAL at id " + expected.getId());
        assertEquals(expected.getDoubleValue(), actual.getDoubleValue(), "DOUBLE at id " + expected.getId());
    }
}
