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

import static org.junit.jupiter.api.Assertions.*;

final class NullSafeDslContract {
    private NullSafeDslContract() {
    }

    static void verify(DataSource dataSource, DatabaseConfiguration database, Consumer<ServiceCollection> extensions) {
        var query = TestClients.configured(dataSource, database, extensions);
        var client = query.getEasyQueryClient();
        var quote = client.getRuntimeContext().getQueryConfiguration().getDialect();
        String from = client.getRuntimeContext().getService(SqlDialect.class) == SqlDialect.HANA ? " FROM DUMMY" : "";
        String source = "SELECT 1 AS " + quote.getQuoteName("id") + ",CAST(1 AS DECIMAL(19,2)) AS " + quote.getQuoteName("amount")
                + ",'same' AS " + quote.getQuoteName("name") + from
                + " UNION ALL SELECT 1,2,'different'" + from
                + " UNION ALL SELECT NULL,1,'left-null'" + from
                + " UNION ALL SELECT 1,NULL,'right-null'" + from
                + " UNION ALL SELECT NULL,NULL,'both-null'" + from;
        var type = CommonTypes.decimal(19, 2);
        var rows = query.queryable(source, TestProduct.class).orderBy(p -> p.name().asc());
        var equals = rows.cloneQueryable().selectColumn(p -> {
            var sql = ProDsl.of(p);
            return sql.nullSafeEquals(sql.nativeCast(p.id(), type), p.amount());
        });
        assertEquals(List.of(true, false, false, false, true), equals.toList());
        assertEquals(equals.toList(), equals.cloneQueryable().toList());
        var distinct = rows.cloneQueryable().selectColumn(p -> {
            var sql = ProDsl.of(p);
            return sql.isDistinctFrom(sql.nativeCast(p.id(), type), p.amount());
        });
        assertEquals(List.of(false, true, true, true, false), distinct.toList());
        var matches = rows.cloneQueryable().where(p -> {
            var sql = ProDsl.of(p);
            sql.nullSafeEquals(sql.nativeCast(p.id(), type), p.amount()).eq(true);
        }).selectColumn(p -> p.name()).toList();
        assertEquals(List.of("both-null", "same"), matches);
        var negated = rows.cloneQueryable().where(p -> {
            var sql = ProDsl.of(p);
            sql.nullSafeEquals(sql.nativeCast(p.id(), type), p.amount()).eq(false);
        }).selectColumn(p -> p.name()).toList();
        assertEquals(List.of("different", "left-null", "right-null"), negated);
        assertEquals(List.of("both-null", "right-null"), rows.cloneQueryable().where(p -> ProDsl.of(p).nullSafeEqualsValue(p.amount(), null).eq(true))
                .selectColumn(p -> p.name()).toList());
        assertEquals(List.of("different", "left-null", "same"), rows.cloneQueryable().where(p -> ProDsl.of(p).isDistinctFromValue(p.amount(), null).eq(true))
                .selectColumn(p -> p.name()).toList());
        assertEquals(List.of("left-null", "same"), rows.cloneQueryable().where(p -> ProDsl.of(p).nullSafeEqualsValue(p.amount(), BigDecimal.ONE).eq(true))
                .selectColumn(p -> p.name()).toList());
        var bound = rows.cloneQueryable().selectColumn(p -> {
            var sql = ProDsl.of(p);
            return sql.nullSafeEquals(sql.value(new BigDecimal("19.99"), type).multiply(100), sql.value(new BigDecimal("1999"), type));
        }).toList();
        assertEquals(List.of(true, true, true, true, true), bound);
        var noMatch = rows.cloneQueryable().where(p -> ProDsl.of(p).nullSafeEqualsValue(p.name(), "O'Reilly 😀").eq(true));
        assertTrue(noMatch.toList().isEmpty());
        var correlated = rows.cloneQueryable().where(p -> p.expression().exists(query.queryable(source, TestProduct.class)
                .where(other -> ProDsl.of(other).nullSafeEquals(other.name(), p.name()).eq(true))));
        assertEquals(5, correlated.toList().size());
    }
}
