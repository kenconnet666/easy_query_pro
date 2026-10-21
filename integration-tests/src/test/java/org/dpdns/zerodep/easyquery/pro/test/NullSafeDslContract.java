package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.inject.ServiceCollection;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import com.easy.query.core.basic.api.select.Query;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class NullSafeDslContract {
    private NullSafeDslContract() {
    }

    static void verify(DataSource dataSource, DatabaseConfiguration database, Consumer<ServiceCollection> extensions) {
        DefaultEasyEntityQuery query = TestClients.configured(dataSource, database, extensions);
        EasyQueryClient client = query.getEasyQueryClient();
        SQLKeyword quote = client.getRuntimeContext().getQueryConfiguration().getDialect();
        String from = client.getRuntimeContext().getService(SqlDialect.class) == SqlDialect.HANA ? " FROM DUMMY" : "";
        String source = "SELECT 1 AS " + quote.getQuoteName("id") + ",CAST(1 AS DECIMAL(19,2)) AS " + quote.getQuoteName("amount")
                + ",'same' AS " + quote.getQuoteName("name") + from
                + " UNION ALL SELECT 1,2,'different'" + from
                + " UNION ALL SELECT NULL,1,'left-null'" + from
                + " UNION ALL SELECT 1,NULL,'right-null'" + from
                + " UNION ALL SELECT NULL,NULL,'both-null'" + from;
        NumericType<BigDecimal> type = CommonTypes.decimal(19, 2);
        EntityQueryable<TestProductProxy, TestProduct> rows = query.queryable(source, TestProduct.class).orderBy(p -> p.name().asc());
        Query<Boolean> equals = rows.cloneQueryable().selectColumn(p -> {
            ProDsl sql = ProDsl.of(p);
            return sql.nullSafeEquals(sql.nativeCast(p.id(), type), p.amount());
        });
        assertEquals(Arrays.asList(true, false, false, false, true), equals.toList());
        assertEquals(equals.toList(), equals.cloneQueryable().toList());
        Query<Boolean> distinct = rows.cloneQueryable().selectColumn(p -> {
            ProDsl sql = ProDsl.of(p);
            return sql.isDistinctFrom(sql.nativeCast(p.id(), type), p.amount());
        });
        assertEquals(Arrays.asList(false, true, true, true, false), distinct.toList());
        List<String> matches = rows.cloneQueryable().where(p -> {
            ProDsl sql = ProDsl.of(p);
            sql.nullSafeEquals(sql.nativeCast(p.id(), type), p.amount()).eq(true);
        }).selectColumn(p -> p.name()).toList();
        assertEquals(Arrays.asList("both-null", "same"), matches);
        List<String> negated = rows.cloneQueryable().where(p -> {
            ProDsl sql = ProDsl.of(p);
            sql.nullSafeEquals(sql.nativeCast(p.id(), type), p.amount()).eq(false);
        }).selectColumn(p -> p.name()).toList();
        assertEquals(Arrays.asList("different", "left-null", "right-null"), negated);
        assertEquals(Arrays.asList("both-null", "right-null"), rows.cloneQueryable().where(p -> ProDsl.of(p).nullSafeEqualsValue(p.amount(), null).eq(true))
                .selectColumn(p -> p.name()).toList());
        assertEquals(Arrays.asList("different", "left-null", "same"), rows.cloneQueryable().where(p -> ProDsl.of(p).isDistinctFromValue(p.amount(), null).eq(true))
                .selectColumn(p -> p.name()).toList());
        assertEquals(Arrays.asList("left-null", "same"), rows.cloneQueryable().where(p -> ProDsl.of(p).nullSafeEqualsValue(p.amount(), BigDecimal.ONE).eq(true))
                .selectColumn(p -> p.name()).toList());
        List<Boolean> bound = rows.cloneQueryable().selectColumn(p -> {
            ProDsl sql = ProDsl.of(p);
            return sql.nullSafeEquals(sql.value(new BigDecimal("19.99"), type).multiply(100), sql.value(new BigDecimal("1999"), type));
        }).toList();
        assertEquals(Arrays.asList(true, true, true, true, true), bound);
        EntityQueryable<TestProductProxy, TestProduct> noMatch = rows.cloneQueryable().where(p -> ProDsl.of(p).nullSafeEqualsValue(p.name(), "O'Reilly 😀").eq(true));
        assertTrue(noMatch.toList().isEmpty());
        EntityQueryable<TestProductProxy, TestProduct> correlated = rows.cloneQueryable().where(p -> p.expression().exists(query.queryable(source, TestProduct.class)
                .where(other -> ProDsl.of(other).nullSafeEquals(other.name(), p.name()).eq(true))));
        assertEquals(5, correlated.toList().size());
    }
}
