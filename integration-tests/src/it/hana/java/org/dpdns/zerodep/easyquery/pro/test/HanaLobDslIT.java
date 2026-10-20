package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.enums.SQLExecuteStrategyEnum;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaTypedLob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaLobDslIT {
    private HanaTestDatabase.TestSchema schema;
    private DefaultEasyEntityQuery query;
    private final String longText = "中😀" + "x".repeat(6001);

    @BeforeEach
    void createFixture() throws SQLException {
        schema = HanaTestDatabase.createSchema("TYPED_LOB");
        try (var statement = schema.connection().createStatement()) {
            statement.execute("CREATE COLUMN TABLE " + schema.qualified("eqp_typed_lob") + " (\"id\" INTEGER PRIMARY KEY, \"ascii\" "
                    + HanaTypes.clob().ddlType(SqlDialect.HANA) + ", \"text\" " + HanaTypes.nclob().ddlType(SqlDialect.HANA)
                    + ", \"searchable\" " + HanaTypes.text().ddlType(SqlDialect.HANA) + ", \"data\" " + HanaTypes.blob().ddlType(SqlDialect.HANA)
                    + ", \"plain\" NVARCHAR(100))");
        }
        query = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
        var full = new HanaTypedLob();
        full.setId(1);
        full.setAscii("ASCII");
        full.setText(longText);
        full.setSearchable("searchable 中😀");
        full.setData(new byte[]{0, 1, (byte) 255});
        var empty = new HanaTypedLob();
        empty.setId(2);
        empty.setAscii("");
        empty.setText("");
        empty.setSearchable("");
        empty.setData(new byte[0]);
        var missing = new HanaTypedLob();
        missing.setId(3);
        assertEquals(3L, query.insertable(List.of(full, empty, missing)).asSchema(s -> schema.name()).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows());
    }

    @AfterEach
    void removeFixture() throws SQLException {
        if (schema != null) schema.close();
    }

    @Test
    void lobLengthsAndNullChecksKeepTheirUnitsAndNullability() {
        var rows = query.queryable(HanaTypedLob.class).asSchema(s -> schema.name()).orderBy(p -> p.id().asc()).select(p -> {
            var hana = HanaDsl.of(p);
            var text = hana.view(p.text(), HanaTypes.nclob());
            var bytes = hana.view(p.data(), HanaTypes.blob());
            return Select.DRAFT.of(text.utf16Length(), bytes.byteLength(), text.isSqlNull(), bytes.isSqlNull(), hana.view(p.ascii(), HanaTypes.clob()).utf16Length(),
                    hana.view(p.searchable(), HanaTypes.text()).utf16Length());
        }).toList();
        assertEquals(Arrays.asList((long) longText.length(), 0L, null), rows.stream().map(r -> r.getValue1()).toList());
        assertEquals(Arrays.asList(3L, 0L, null), rows.stream().map(r -> r.getValue2()).toList());
        assertEquals(List.of(false, false, true), rows.stream().map(r -> r.getValue3()).toList());
        assertEquals(List.of(false, false, true), rows.stream().map(r -> r.getValue4()).toList());
        assertEquals(Arrays.asList(5L, 0L, null), rows.stream().map(r -> r.getValue5()).toList());
        assertEquals(Arrays.asList((long) "searchable 中😀".length(), 0L, null), rows.stream().map(r -> r.getValue6()).toList());
        assertEquals(1L, query.queryable(HanaTypedLob.class).asSchema(s -> schema.name()).where(p -> HanaDsl.of(p).view(p.text(), HanaTypes.nclob()).isSqlNull().eq(true)).count());
    }

    @Test
    void slicesAreScalarsAndTextConversionDoesNotTruncateLongValues() {
        var selected = query.queryable(HanaTypedLob.class).asSchema(s -> schema.name()).where(p -> p.id().eq(1)).select(p -> {
            var hana = HanaDsl.of(p);
            var text = hana.view(p.text(), HanaTypes.nclob());
            return Select.DRAFT.<String, String, byte[], String, String>of(text.sliceUtf16(2, 2), text.sliceUtf16(1, 0), hana.view(p.data(), HanaTypes.blob()).sliceBytes(2, 2),
                    text.asText(), hana.view(p.searchable(), HanaTypes.text()).asText());
        });
        var row = selected.singleNotNull();
        assertEquals("😀", row.getValue1());
        assertEquals("", row.getValue2());
        assertArrayEquals(new byte[]{1, (byte) 255}, row.getValue3());
        assertEquals(longText, row.getValue4());
        assertEquals("searchable 中😀", row.getValue5());
        assertEquals(longText, selected.cloneQueryable().singleNotNull().getValue4());
        String half = query.queryable(HanaTypedLob.class).asSchema(s -> schema.name()).where(p -> p.id().eq(1))
                .selectColumn(p -> HanaDsl.of(p).view(p.text(), HanaTypes.nclob()).sliceUtf16(2, 1)).singleNotNull();
        assertEquals(1, half.length());
        assertTrue(Character.isHighSurrogate(half.charAt(0)));
        assertEquals(3, query.queryable(HanaTypedLob.class).asSchema(s -> schema.name())
                .selectColumn(p -> HanaDsl.of(p).view(p.text(), HanaTypes.nclob()).asText()).distinct().toList().size());
    }

    @Test
    void detachedBinaryValuesAndNativeConversionsUseExistingHandlers() {
        byte[] input = {4, 5, 6};
        var selected = query.queryable(HanaTypedLob.class).asSchema(s -> schema.name()).where(p -> p.id().eq(1))
                .selectColumn(p -> HanaDsl.of(p).value(input, HanaTypes.blob()).sliceBytes(1, 3));
        input[0] = 99;
        assertArrayEquals(new byte[]{4, 5, 6}, selected.singleNotNull());
        // 3.2.17 APT boxes primitive array components; the public type assertion restores the entity's byte[] mapping.
        assertArrayEquals(new byte[]{0, 1, (byte) 255}, query.queryable(HanaTypedLob.class).asSchema(s -> schema.name()).where(p -> p.id().eq(1))
                .selectColumn(p -> HanaDsl.of(p).view(p.data().asAnyType(byte[].class), HanaTypes.blob())).singleNotNull());
        assertEquals("unicode 中😀", query.queryable(HanaTypedLob.class).asSchema(s -> schema.name()).where(p -> p.id().eq(1))
                .selectColumn(p -> HanaDsl.of(p).value("unicode 中😀", HanaTypes.nclob()).asText()).singleNotNull());
        assertNull(query.queryable(HanaTypedLob.class).asSchema(s -> schema.name()).where(p -> p.id().eq(1))
                .selectColumn(p -> HanaDsl.of(p).value((byte[]) null, HanaTypes.blob()).sliceBytes(1, 1)).toList().get(0));
        assertArrayEquals(new byte[0], query.queryable(HanaTypedLob.class).asSchema(s -> schema.name()).where(p -> p.id().eq(1))
                .selectColumn(p -> HanaDsl.of(p).value(new byte[0], HanaTypes.blob()).sliceBytes(1, 0)).singleNotNull());
    }
}
