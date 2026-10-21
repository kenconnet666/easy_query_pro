package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.inject.ServiceCollection;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;

import javax.sql.DataSource;
import java.util.List;
import java.util.function.Consumer;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.type.TextType;
import com.easy.query.core.basic.api.select.Query;
import java.util.Collections;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class TextDslContract {
    private TextDslContract() {
    }

    static void verify(DataSource dataSource, DatabaseConfiguration database, Consumer<ServiceCollection> extensions) {
        DefaultEasyEntityQuery query = TestClients.configured(dataSource, database, extensions);
        QueryRuntimeContext runtime = query.getRuntimeContext();
        SQLKeyword quote = runtime.getQueryConfiguration().getDialect();
        SqlDialect dialect = runtime.getService(SqlDialect.class);
        String source = "SELECT 1 AS " + quote.getQuoteName("id") + ",'😀a' AS " + quote.getQuoteName("name")
                + (dialect == SqlDialect.HANA ? " FROM DUMMY" : "");
        EntityQueryable<TestProductProxy, TestProduct> rows = query.queryable(source, TestProduct.class);
        TextType type = CommonTypes.unicodeText(20);
        String value = "O'Reilly 中😀{0}";
        Query<String> bound = rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).value(value, type));
        assertEquals(value, bound.singleNotNull());
        assertEquals(value, bound.cloneQueryable().singleNotNull());
        assertEquals("AB'中😀", rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).value("ab'中😀", type).toUpper()).singleNotNull());
        assertEquals("", rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).value("", type)).singleNotNull());
        List<String> nulls = rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullValue(type)).toList();
        assertEquals(1, nulls.size());
        assertNull(nulls.get(0));
        assertEquals(3, rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).value("a😀b", type).length()).singleNotNull());
        assertEquals(2, rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).value("a😀b", type).indexOf("b")).singleNotNull());
        assertEquals("1!", rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nativeCast(p.id(), type).concat("!")).singleNotNull());
        String expected = dialect == SqlDialect.HANA ? "😀" : "😀a";
        assertEquals(expected, rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nativeCast(p.name(), CommonTypes.unicodeText(2))).singleNotNull());
        String maximum = String.join("", Collections.nCopies(2500, "😀"));
        assertEquals(maximum, rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).value(maximum, CommonTypes.unicodeText(5000))).singleNotNull());
        assertThrows(IllegalArgumentException.class, () -> rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).value("😀a", CommonTypes.unicodeText(2))).toList());
        assertThrows(IllegalArgumentException.class, () -> rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).value("a\0", type)).toList());
        assertEquals(Arrays.asList(value), bound.toList());
    }
}
