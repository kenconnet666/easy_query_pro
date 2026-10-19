package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.core.inject.ServiceCollection;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.function.Executable;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

final class DecimalDslContract {
    private DecimalDslContract() {
    }

    static void verify(DataSource dataSource, DatabaseConfiguration database, Consumer<ServiceCollection> extensions) {
        var client = EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(dataSource)
                .useDatabaseConfigure(database).customConfigure(extensions).build();
        var query = new DefaultEasyEntityQuery(client);
        var dialect = client.getRuntimeContext().getService(SqlDialect.class);
        var quote = client.getRuntimeContext().getQueryConfiguration().getDialect();
        String source = "SELECT 1 AS " + quote.getQuoteName("id") + ",CAST(12.3456 AS DECIMAL(19,4)) AS "
                + quote.getQuoteName("amount") + (dialect == SqlDialect.HANA ? " FROM DUMMY" : "");
        var rows = query.queryable(source, TestProduct.class);
        var money = CommonTypes.decimal(19, 2);
        var exact = rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).value(new BigDecimal("19.99"), money).multiply(100));
        equalNumber("1999", exact.singleNotNull());
        equalNumber("1999", exact.cloneQueryable().singleNotNull());
        var nulls = rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullValue(money)).toList();
        assertEquals(1, nulls.size());
        assertNull(nulls.get(0), "Typed NULL must not turn into a primitive default");
        equalNumber("0", rows.cloneQueryable().selectColumn(p -> ProDsl.of(p)
                .value(new BigDecimal(BigInteger.ZERO, Integer.MAX_VALUE), money)).singleNotNull());
        assertEquals(1, rows.cloneQueryable().where(p -> ProDsl.of(p).nullValue(money).isNull()).toList().size());
        assertThrows(IllegalArgumentException.class, () -> rows.cloneQueryable()
                .selectColumn(p -> ProDsl.of(p).value(new BigDecimal("19.999"), money)).toList());

        // CAST 遵循显式选择的原生语义，不能把三库不同的舍入/溢出行为当成通用保证。
        String rounded = dialect == SqlDialect.HANA ? "12.34" : "12.35";
        equalNumber(rounded, rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nativeCast(p.amount(), money)).singleNotNull());
        equalNumber(rounded, rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nativeCast(p.amount().sumBigDecimal(), money)).singleNotNull());
        var correlated = rows.cloneQueryable().where(p -> p.expression().exists(query.queryable(source, TestProduct.class)
                .where(other -> {
                    other.id().eq(p.id());
                    ProDsl.of(other).nativeCast(other.amount(), money).eq(ProDsl.of(p).nativeCast(p.amount(), money));
                })));
        assertEquals(1, correlated.toList().size());
        assertEquals(1, correlated.cloneQueryable().toList().size());
        var overflow = rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nativeCast(p.amount(), CommonTypes.decimal(2, 1)));
        if (dialect == SqlDialect.MYSQL) equalNumber("9.9", overflow.singleNotNull());
        else assertThrows(RuntimeException.class, (Executable) overflow::singleNotNull);
        equalNumber("1999", exact.singleNotNull());
    }

    private static void equalNumber(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), actual.toPlainString());
    }
}
