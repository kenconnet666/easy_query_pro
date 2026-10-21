package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft2Proxy;
import com.easy.query.core.proxy.core.draft.Draft2;
import com.easy.query.core.common.ToSQLResult;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MySqlUnsignedTypeTest {
    @Test
    void widthsUseJavaTypesThatRepresentTheirEntireUnsignedRange() {
        assertEquals((short) 255, MySqlTypes.unsignedTinyint().prepareValue((short) 255));
        assertEquals(65535, MySqlTypes.unsignedSmallint().prepareValue(65535));
        assertEquals(16777215, MySqlTypes.unsignedMediumint().prepareValue(16777215));
        assertEquals(4294967295L, MySqlTypes.unsignedInt().prepareValue(4294967295L));
        BigInteger maximum = new BigInteger("18446744073709551615");
        assertEquals(maximum, MySqlTypes.unsignedBigint().prepareValue(maximum));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedTinyint().prepareValue((short) 256));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedSmallint().prepareValue(65536));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedMediumint().prepareValue(16777216));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedInt().prepareValue(4294967296L));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedBigint().prepareValue(maximum.add(BigInteger.ONE)));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedBigint().prepareValue(BigInteger.valueOf(-1)));
        assertThrows(IllegalArgumentException.class, () -> MySqlTypes.unsignedInt().ddlType(SqlDialect.POSTGRESQL));
        assertEquals("BIGINT UNSIGNED", MySqlTypes.unsignedBigint().ddlType(SqlDialect.MYSQL));
        assertEquals("CAST({0} AS UNSIGNED)", MySqlTypes.unsignedBigint().castTemplate(SqlDialect.MYSQL));
        assertNull(MySqlTypes.unsignedInt().prepareValue(null));
    }

    @Test
    void bigintFacadePreservesNumericOverridesAndParameterValues() {
        AtomicInteger calls = new AtomicInteger();
        class ApplicationDsl extends MySqlDsl {
            ApplicationDsl(ExpressionScope scope) {
                super(scope);
            }

            @Override
            public <N extends Number> NumberTypeExpression<N> value(N value, NumericType<N> type) {
                calls.incrementAndGet();
                return super.value(value, type);
            }
        }
        BigInteger maximum = MySqlTypes.unsignedBigint().maximum();
        DefaultEasyEntityQuery client = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        EntityQueryable<Draft2Proxy<BigInteger, BigInteger>, Draft2<BigInteger, BigInteger>> query = client.queryable(TestProduct.class).select(p -> {
            ApplicationDsl dsl = ProDsl.of(p, scope -> new ApplicationDsl(scope));
            return Select.DRAFT.of(dsl.value(maximum, MySqlTypes.unsignedBigint()),
                    dsl.value(null, MySqlTypes.unsignedBigint()));
        });
        ToSQLResult result = query.toSQLResult();
        assertEquals(2, calls.get());
        assertEquals(Arrays.asList(maximum, null),
                result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
        assertEquals(2, calls.get());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class)
                .selectColumn(p -> MySqlDsl.of(p).value(maximum.add(BigInteger.ONE), MySqlTypes.unsignedBigint())));
    }
}
