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
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import org.dpdns.zerodep.easyquery.pro.dsl.type.TextType;
import java.util.Arrays;
import java.util.Collections;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import java.util.stream.Collectors;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.basic.jdbc.tx.Transaction;

import static org.junit.jupiter.api.Assertions.*;

class HanaCharacterIT {
    @Test
    void nativeTextValuesPreserveCharactersAndKeepNumericPaddingExplicit() throws SQLException {
        DefaultEasyEntityQuery client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
        List<TextType> types = Arrays.asList(HanaTypes.varchar(20), HanaTypes.nvarchar(20), HanaTypes.alphanum(6));
        List<List<String>> values = Arrays.asList(Arrays.asList("", "12", "O'R !", " "), Arrays.asList("", "中😀", String.join("", Collections.nCopies(10, "😀")), "O'R !"),
                Arrays.asList("", "000012", "000002", "12 ", " 12", "-1", "a!", "中😀", "😀😀😀"));
        for (int i = 0; i < types.size(); i++) {
            TextType type = types.get(i);
            List<String> texts = values.get(i);
            try (HanaTestDatabase.TestTable fixture = HanaTestDatabase.createTable("NATIVE_TEXT", "\"id\" INTEGER PRIMARY KEY, \"name\" " + type.ddlType(SqlDialect.HANA) + ", \"amount\" DECIMAL(16,2)")) {
                ArrayList<TestProduct> entities = new ArrayList<TestProduct>();
                for (String text : texts)
                    entities.add(new TestProduct(entities.size() + 1, type.prepareValue(text), null));
                entities.add(new TestProduct(entities.size() + 1, null, null));
                assertEquals(entities.size(), client.insertable(entities).asTable(fixture.name()).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows());
                EntityQueryable<TestProductProxy, TestProduct> rows = client.queryable(TestProduct.class).asTable(fixture.name());
                List<TestProduct> actual = rows.cloneQueryable().orderBy(p -> p.id().asc()).toList();
                assertEquals(entities.stream().map(TestProduct::getName).collect(Collectors.toList()), actual.stream().map(TestProduct::getName).collect(Collectors.toList()));
                for (String text : texts) {
                    Query<String> selection = rows.cloneQueryable().where(p -> p.id().eq(1)).selectColumn(p -> ProDsl.of(p).value(text, type));
                    assertEquals(text, selection.singleNotNull());
                    assertEquals(text, selection.cloneQueryable().singleNotNull());
                }
                assertNull(rows.cloneQueryable().where(p -> p.id().eq(1)).selectColumn(p -> ProDsl.of(p).nullValue(type)).toList().get(0));
                if (i == 2) {
                    assertEquals(Arrays.asList("000002", "000012"), rows.cloneQueryable().where(p -> p.id().in(Arrays.asList(2, 3))).orderBy(p -> p.name().asc()).selectColumn(p -> p.name()).toList());
                    assertEquals("000012", rows.cloneQueryable().where(p -> p.id().eq(1)).selectColumn(p -> {
                        ProDsl common = ProDsl.of(p);
                        return common.nativeCast(common.value("12", CommonTypes.unicodeText(10)), type);
                    }).singleNotNull());
                    assertEquals("000012!", rows.cloneQueryable().where(p -> p.id().eq(2)).selectColumn(p -> ProDsl.of(p).nativeCast(p.name(), type).concat("!")).singleNotNull());
                }
            }
        }
    }

    @Test
    void shorttextStorageKeepsOrdinaryStringQueriesAndNulls() throws SQLException {
        DefaultEasyEntityQuery client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
        try (HanaTestDatabase.TestTable fixture = HanaTestDatabase.createTable("SHORTTEXT", "\"id\" INTEGER PRIMARY KEY, \"name\" " + HanaTypes.shortText(20).ddlType(SqlDialect.HANA) + ", \"amount\" DECIMAL(16,2)")) {
            List<TestProduct> values = Arrays.asList(new TestProduct(1, "中😀 Hello", null), new TestProduct(2, "", null), new TestProduct(3, null, null));
            client.insertable(values).asTable(fixture.name()).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows();
            EntityQueryable<TestProductProxy, TestProduct> rows = client.queryable(TestProduct.class).asTable(fixture.name());
            assertEquals(values.stream().map(TestProduct::getName).collect(Collectors.toList()), rows.cloneQueryable().orderBy(p -> p.id().asc()).selectColumn(p -> p.name()).toList());
            assertEquals("中😀 HELLO", rows.cloneQueryable().where(p -> p.id().eq(1)).selectColumn(p -> p.name().toUpper()).singleNotNull());
            assertEquals(3, rows.cloneQueryable().selectColumn(p -> p.name()).distinct().toList().size());
            assertEquals(1, rows.cloneQueryable().where(p -> p.name().isNull()).count());
            try (Transaction transaction = client.getEasyQueryClient().beginTransaction()) {
                client.updatable(TestProduct.class).asTable(fixture.name()).setColumns(p -> p.name().set("changed")).where(p -> p.id().eq(1)).executeRows();
                transaction.rollback();
            }
            assertEquals("中😀 Hello", rows.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull().getName());
        }
    }
}
