package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HanaScalarFunctionsIT {
    private DefaultEasyEntityQuery query;

    @BeforeEach
    void createClient() throws SQLException {
        var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(HanaTestDatabase.dataSource())
                .useDatabaseConfigure(new HanaDatabaseConfiguration()).build();
        query = new DefaultEasyEntityQuery(client);
    }

    @Test
    void stringFunctionsRetainBindingUnicodeAndIndexSemantics() {
        String value = "O'Reilly %_!\\中文";
        var source = source(value, BigDecimal.ONE);
        assertEquals(value.length(), source.cloneQueryable().selectColumn(p -> p.name().length()).toList().get(0));
        assertEquals(value.indexOf("_!"), source.cloneQueryable().selectColumn(p -> p.name().indexOf("_!")).toList().get(0));
        assertEquals(-1, source.cloneQueryable().selectColumn(p -> p.name().indexOf("missing")).toList().get(0));
        assertEquals("O'Reilly", source.cloneQueryable().selectColumn(p -> p.name().subString(0, 8)).toList().get(0));
        assertEquals(value + "-tail", source.cloneQueryable()
                .selectColumn(p -> p.name().concat(parts -> { parts.value("-"); parts.value("tail"); }))
                .toList().get(0));
        assertNull(source(null, BigDecimal.ONE).selectColumn(p -> p.name().concat("suffix")).toList().get(0));
    }

    @Test
    void likeTreatsWildcardCharactersAsValuesAndCanBeRenderedRepeatedly() {
        var selection = source("prefix%_!\\suffix", BigDecimal.ONE)
                .where(p -> p.name().toUpper().like("%_!\\"));
        var first = selection.toSQLResult();
        var second = selection.toSQLResult();
        assertEquals(first.getSQL(), second.getSQL());
        assertEquals(first.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList(),
                second.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertFalse(first.getSQL().contains("prefix"));
        assertEquals(1, selection.toList().size());
        assertTrue(source("ordinary", BigDecimal.ONE).where(p -> p.name().toUpper().like("%_!\\")).toList().isEmpty());
        assertEquals(1, source("prefix%_!\\suffix", BigDecimal.ONE)
                .where(p -> p.name().startsWith("prefix%_")).where(p -> p.name().endsWith("!\\suffix")).toList().size());
    }

    @Test
    void mathUsesHanaFunctionsWithoutChangingNumericMeaning() {
        assertDecimal("8", source("math", new BigDecimal("2")).selectColumn(p -> p.amount().pow(new BigDecimal("3"))).toList().get(0));
        assertDecimal("2", source("math", new BigDecimal("100")).selectColumn(p -> p.amount().log10()).toList().get(0));
        assertDecimal("0", source("math", BigDecimal.ONE).selectColumn(p -> p.amount().log()).toList().get(0));
        assertDecimal("-1", source("math", new BigDecimal("-1.8")).selectColumn(p -> p.amount().truncate()).toList().get(0));
        assertDecimal("2", source("math", new BigDecimal("1.2")).selectColumn(p -> p.amount().ceiling()).toList().get(0));
    }

    private EntityQueryable<TestProductProxy, TestProduct> source(String text, BigDecimal number) {
        // 真实执行派生表即可验证表达式，不为只读函数测试创建和清理实体表。
        return query.queryable("SELECT 1 AS \"id\",CAST(? AS NVARCHAR(255)) AS \"name\","
                + "CAST(? AS DECIMAL(19,4)) AS \"amount\" FROM DUMMY", TestProduct.class, Arrays.asList(text, number));
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
