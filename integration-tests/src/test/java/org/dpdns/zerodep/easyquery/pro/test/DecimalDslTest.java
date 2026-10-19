package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DecimalDslTest {
    record Backend(DatabaseConfiguration database, Consumer<ServiceCollection> extensions, SqlDialect dialect) {
        DefaultEasyEntityQuery client() {
            return TestClients.offline(database, extensions);
        }
    }

    static Stream<Backend> backends() {
        return Stream.of(new Backend(new MySQLDatabaseConfiguration(), MySqlExtensions::configure, SqlDialect.MYSQL),
                new Backend(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure, SqlDialect.POSTGRESQL),
                new Backend(new HanaDatabaseConfiguration(), HanaExtensions::configure, SqlDialect.HANA));
    }

    @ParameterizedTest
    @MethodSource("backends")
    void bindsOncePerOccurrenceAndComposesThroughExistingOperatorsAndClone(Backend backend) {
        var decimal = CommonTypes.decimal(19, 2);
        var query = backend.client().queryable(TestProduct.class)
                .where(p -> ProDsl.of(p).nativeCast(p.amount(), decimal).gt(new BigDecimal("3.00")))
                .orderBy(p -> ProDsl.of(p).nativeCast(p.amount(), decimal).desc())
                .selectColumn(p -> ProDsl.of(p).value(new BigDecimal("19.99"), decimal).multiply(100));
        var sql = query.toSQLResult();
        assertTrue(sql.getSQL().contains("CAST(? AS DECIMAL(19,2))"), sql.getSQL());
        assertFalse(sql.getSQL().contains("19.99"));
        assertEquals(List.of(new BigDecimal("19.99"), 100, new BigDecimal("3.00")),
                sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        var copy = query.cloneQueryable().toSQLResult();
        assertEquals(sql.getSQL(), copy.getSQL());
        assertEquals(sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList(),
                copy.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(sql.getSQL(), query.toSQL());
    }

    @ParameterizedTest
    @MethodSource("backends")
    void typedNullAndJoinedColumnsKeepTheirOwnAlias(Backend backend) {
        var decimal = CommonTypes.decimal(8, 2);
        var client = backend.client();
        var nullResult = client.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).nullValue(decimal)).toSQLResult();
        assertEquals(Arrays.asList((Object) null), nullResult.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        var query = client.queryable(TestProduct.class).leftJoin(TestProduct.class, (a, b) -> a.id().eq(b.id()))
                .selectColumn((a, b) -> ProDsl.of(a).nativeCast(b.amount(), decimal));
        assertTrue(query.toSQL().matches("(?s).*CAST\\(t1\\..*amount.* AS DECIMAL\\(8,2\\)\\).*"), query.toSQL());
        assertEquals(query.toSQL(), query.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("backends")
    void rejectsForeignColumnsAndExpressionReuseWithoutQueryTables(Backend backend) {
        var client = backend.client();
        var foreign = new AtomicReference<TestProductProxy>();
        var expression = new AtomicReference<NumberTypeExpression<BigDecimal>>();
        client.queryable(TestProduct.class).where(p -> {
            foreign.set(p);
            expression.set(ProDsl.of(p).value(BigDecimal.ONE, CommonTypes.decimal(9, 2)));
        });
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).nativeCast(foreign.get().amount(), CommonTypes.decimal(9, 2))).toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(TestProduct.class)
                .selectColumn(p -> expression.get()).toSQL());
        assertThrows(IllegalArgumentException.class, () -> backend.client().queryable(TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).nativeCast(foreign.get().amount(), CommonTypes.decimal(9, 2))));
        assertThrows(IllegalArgumentException.class, () -> backend.client().queryable(TestProduct.class)
                .selectColumn(p -> expression.get()).toSQL());
    }

    @ParameterizedTest
    @MethodSource("backends")
    void extensionRegistrationIsIdempotentAndClientLocal(Backend backend) {
        var client = TestClients.offline(backend.database(), services -> {
            backend.extensions().accept(services);
            backend.extensions().accept(services);
        });
        assertEquals(backend.dialect(), client.getRuntimeContext().getService(SqlDialect.class));
        client.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).nullValue(CommonTypes.decimal(5, 2))).toSQL();
        assertThrows(IllegalStateException.class, () -> TestClients.offline(backend.database())
                .queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).nullValue(CommonTypes.decimal(5, 2))));
    }

    @ParameterizedTest
    @MethodSource("backends")
    void composesInsideCorrelatedSubqueriesAndWriteSetters(Backend backend) {
        var client = backend.client();
        var type = CommonTypes.decimal(19, 2);
        var query = client.queryable(TestProduct.class).where(p -> p.expression().exists(client.queryable(TestProduct.class)
                .where(other -> {
                    other.id().eq(p.id());
                    ProDsl.of(other).nativeCast(other.amount(), type).gt(ProDsl.of(p).nativeCast(p.amount(), type));
                })));
        assertTrue(query.toSQL().contains("EXISTS"), query.toSQL());
        assertEquals(query.toSQL(), query.cloneQueryable().toSQL());
        var update = client.updatable(TestProduct.class).setColumns(p -> p.amount().set(ProDsl.of(p).value(new BigDecimal("19.99"), type)))
                .where(p -> p.id().eq(1));
        assertTrue(update.toSQL().contains("CAST(? AS DECIMAL(19,2))"), update.toSQL());
    }

    @Test
    void rejectsConflictingOrMismatchedInstallations() {
        assertThrows(IllegalStateException.class, () -> TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
            MySqlExtensions.configure(services);
            HanaExtensions.configure(services);
        }));
        assertThrows(IllegalStateException.class, () -> TestClients.offline(new MySQLDatabaseConfiguration(), HanaExtensions::configure)
                .queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).nullValue(CommonTypes.decimal(5, 2))));
    }

    @Test
    void decimalValueValidationRequiresExactFitWithoutRoundingOrScaleExpansion() {
        var decimal = CommonTypes.decimal(5, 2);
        for (String value : List.of("999.99", "-999.99", "0.0100", "0.000", "1E+2")) {
            decimal.prepareValue(new BigDecimal(value));
        }
        decimal.prepareValue(null);
        decimal.prepareValue(new BigDecimal(BigInteger.ZERO, Integer.MAX_VALUE));
        for (BigDecimal value : List.of(new BigDecimal("1000"), new BigDecimal("0.001"),
                new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE), new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE))) {
            assertThrows(IllegalArgumentException.class, () -> decimal.prepareValue(value));
        }
        assertThrows(IllegalArgumentException.class, () -> CommonTypes.decimal(0, 0));
        assertThrows(IllegalArgumentException.class, () -> CommonTypes.decimal(39, 0));
        assertThrows(IllegalArgumentException.class, () -> CommonTypes.decimal(9, -1));
        assertThrows(IllegalArgumentException.class, () -> CommonTypes.decimal(9, 10));
        assertThrows(IllegalArgumentException.class, () -> CommonTypes.decimal(38, 31));
    }
}
