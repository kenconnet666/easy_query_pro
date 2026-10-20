package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.enums.SQLExecuteStrategyEnum;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaCharacterIT {
    @Test
    void nativeTextValuesPreserveCharactersAndKeepNumericPaddingExplicit() throws SQLException {
        var client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
        var types = List.of(HanaTypes.varchar(20), HanaTypes.nvarchar(20), HanaTypes.alphanum(6));
        var values = List.of(List.of("", "12", "O'R !", " "), List.of("", "中😀", "😀".repeat(10), "O'R !"),
                List.of("", "000012", "000002", "12 ", " 12", "-1", "a!", "中😀", "😀😀😀"));
        for (int i = 0; i < types.size(); i++) {
            var type = types.get(i);
            var texts = values.get(i);
            try (var fixture = HanaTestDatabase.createTable("NATIVE_TEXT", "\"id\" INTEGER PRIMARY KEY, \"name\" " + type.ddlType(SqlDialect.HANA) + ", \"amount\" DECIMAL(16,2)")) {
                var entities = new ArrayList<TestProduct>();
                for (String text : texts)
                    entities.add(new TestProduct(entities.size() + 1, type.prepareValue(text), null));
                entities.add(new TestProduct(entities.size() + 1, null, null));
                assertEquals(entities.size(), client.insertable(entities).asTable(fixture.name()).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows());
                var rows = client.queryable(TestProduct.class).asTable(fixture.name());
                var actual = rows.cloneQueryable().orderBy(p -> p.id().asc()).toList();
                assertEquals(entities.stream().map(TestProduct::getName).toList(), actual.stream().map(TestProduct::getName).toList());
                for (String text : texts) {
                    var selection = rows.cloneQueryable().where(p -> p.id().eq(1)).selectColumn(p -> ProDsl.of(p).value(text, type));
                    assertEquals(text, selection.singleNotNull());
                    assertEquals(text, selection.cloneQueryable().singleNotNull());
                }
                assertNull(rows.cloneQueryable().where(p -> p.id().eq(1)).selectColumn(p -> ProDsl.of(p).nullValue(type)).toList().get(0));
                if (i == 2) {
                    assertEquals(List.of("000002", "000012"), rows.cloneQueryable().where(p -> p.id().in(List.of(2, 3))).orderBy(p -> p.name().asc()).selectColumn(p -> p.name()).toList());
                    assertEquals("000012", rows.cloneQueryable().where(p -> p.id().eq(1)).selectColumn(p -> {
                        var common = ProDsl.of(p);
                        return common.nativeCast(common.value("12", CommonTypes.unicodeText(10)), type);
                    }).singleNotNull());
                    assertEquals("000012!", rows.cloneQueryable().where(p -> p.id().eq(2)).selectColumn(p -> ProDsl.of(p).nativeCast(p.name(), type).concat("!")).singleNotNull());
                }
            }
        }
    }

    @Test
    void shorttextStorageKeepsOrdinaryStringQueriesAndNulls() throws SQLException {
        var client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
        try (var fixture = HanaTestDatabase.createTable("SHORTTEXT", "\"id\" INTEGER PRIMARY KEY, \"name\" " + HanaTypes.shortText(20).ddlType(SqlDialect.HANA) + ", \"amount\" DECIMAL(16,2)")) {
            var values = List.of(new TestProduct(1, "中😀 Hello", null), new TestProduct(2, "", null), new TestProduct(3, null, null));
            client.insertable(values).asTable(fixture.name()).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows();
            var rows = client.queryable(TestProduct.class).asTable(fixture.name());
            assertEquals(values.stream().map(TestProduct::getName).toList(), rows.cloneQueryable().orderBy(p -> p.id().asc()).selectColumn(p -> p.name()).toList());
            assertEquals("中😀 HELLO", rows.cloneQueryable().where(p -> p.id().eq(1)).selectColumn(p -> p.name().toUpper()).singleNotNull());
            assertEquals(3, rows.cloneQueryable().selectColumn(p -> p.name()).distinct().toList().size());
            assertEquals(1, rows.cloneQueryable().where(p -> p.name().isNull()).count());
            try (var transaction = client.getEasyQueryClient().beginTransaction()) {
                client.updatable(TestProduct.class).asTable(fixture.name()).setColumns(p -> p.name().set("changed")).where(p -> p.id().eq(1)).executeRows();
                transaction.rollback();
            }
            assertEquals("中😀 Hello", rows.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull().getName());
        }
    }
}
