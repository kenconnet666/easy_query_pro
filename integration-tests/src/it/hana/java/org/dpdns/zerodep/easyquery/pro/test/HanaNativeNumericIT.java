package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import javax.sql.DataSource;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import java.util.Arrays;
import java.util.Collections;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;

import static org.junit.jupiter.api.Assertions.*;

class HanaNativeNumericIT {
    @Test
    void basicNumericWidthsSurviveExtremeParametersAndStorage() throws SQLException {
        DefaultEasyEntityQuery client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
        try (HanaTestDatabase.TestTable fixture = HanaTestDatabase.createTable("BASIC_NUMERIC",
                BasicNumericContract.columns(client, SqlDialect.HANA))) {
            BasicNumericContract.verify(client, fixture.name());
        }
    }

    @Test
    void decimalFloatingExtremesAndFixedScaleSurviveBatchBindingAndStorage() throws SQLException {
        DataSource source = HanaTestDatabase.dataSource();
        DefaultEasyEntityQuery client = TestClients.configured(source, new HanaDatabaseConfiguration(), HanaExtensions::configure);
        List<NumericType<BigDecimal>> types = Arrays.asList(HanaTypes.smallDecimal(), HanaTypes.floatingDecimal(), HanaTypes.decimal(38, 38));
        List<List<BigDecimal>> values = Arrays.asList(Arrays.asList(new BigDecimal("9.999999999999999E384"), new BigDecimal("-1E384"), new BigDecimal("1E-398"), new BigDecimal("9.999999999999999E-383"), BigDecimal.ZERO),
                Arrays.asList(new BigDecimal("9.999999999999999999999999999999999E6144"), new BigDecimal("-1E6144"), new BigDecimal("1E-6176"), BigDecimal.ZERO),
                Arrays.asList(new BigDecimal("0." + String.join("", Collections.nCopies(38, "9"))), new BigDecimal("-1E-38"), BigDecimal.ZERO));
        for (int i = 0; i < types.size(); i++) {
            NumericType<BigDecimal> type = types.get(i);
            try (HanaTestDatabase.TestTable fixture = HanaTestDatabase.createTable("NATIVE_DECIMAL", "\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(20), \"amount\" " + type.ddlType(SqlDialect.HANA))) {
                NativeDecimalContract.verify(client, fixture.name(), type, values.get(i));
                NativeDecimalContract.nativeRounding(client, fixture.name(), HanaTypes.smallDecimal(), "12345678901234567", "12345678901234570");
            }
        }
    }

    @Test
    void ordinaryEntityBindingPreservesNativeExponentConversionAndRollback() throws SQLException {
        DefaultEasyEntityQuery client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
        try (HanaTestDatabase.TestTable fixture = HanaTestDatabase.createTable("NATIVE_EXPONENT", "\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(20), \"amount\" DECIMAL")) {
            BigDecimal huge = new BigDecimal("1E6144").setScale(0);
            client.insertable(new TestProduct(1, "exponent", huge)).asTable(fixture.name()).executeRows();
            NativeDecimalContract.equalValue(huge, client.queryable(TestProduct.class).asTable(fixture.name()).singleNotNull().getAmount());
            try (Transaction transaction = client.getEasyQueryClient().beginTransaction()) {
                client.updatable(TestProduct.class).asTable(fixture.name()).setColumns(p -> p.amount().set(new BigDecimal("1E-6176"))).where(p -> p.id().eq(1)).executeRows();
                NativeDecimalContract.equalValue(new BigDecimal("1E-6176"), client.queryable(TestProduct.class).asTable(fixture.name()).singleNotNull().getAmount());
                transaction.rollback();
            }
            NativeDecimalContract.equalValue(huge, client.queryable(TestProduct.class).asTable(fixture.name()).singleNotNull().getAmount());
            // Raw entity writes retain server underflow; typed values reject it before binding.
            client.updatable(TestProduct.class).asTable(fixture.name()).setColumns(p -> p.amount().set(new BigDecimal("1E-6177"))).where(p -> p.id().eq(1)).executeRows();
            NativeDecimalContract.equalValue(BigDecimal.ZERO, client.queryable(TestProduct.class).asTable(fixture.name()).singleNotNull().getAmount());
            assertThrows(RuntimeException.class, () -> client.updatable(TestProduct.class).asTable(fixture.name())
                    .setColumns(p -> p.amount().set(new BigDecimal("1E6145"))).where(p -> p.id().eq(1)).executeRows());
            NativeDecimalContract.equalValue(BigDecimal.ZERO, client.queryable(TestProduct.class).asTable(fixture.name()).singleNotNull().getAmount());
        }
    }

    @Test
    void tinyintUsesAnUnsignedShortValueAndRejectsOutOfRangeStorage() throws SQLException {
        DataSource source = HanaTestDatabase.dataSource();
        DefaultEasyEntityQuery client = TestClients.configured(source, new HanaDatabaseConfiguration(), HanaExtensions::configure);
        try (HanaTestDatabase.TestTable fixture = HanaTestDatabase.createTable("NATIVE_TINY", "\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(20), \"amount\" " + HanaTypes.tinyint().ddlType(SqlDialect.HANA))) {
            client.insertable(new TestProduct(1, "tiny", new BigDecimal("255"))).asTable(fixture.name()).executeRows();
            EntityQueryable<TestProductProxy, TestProduct> rows = client.queryable(TestProduct.class).asTable(fixture.name());
            assertEquals((short) 255, rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nativeCast(p.amount(), HanaTypes.tinyint())).singleNotNull());
            assertEquals((short) 255, rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).value((short) 255, HanaTypes.tinyint())).singleNotNull());
            assertNull(rows.cloneQueryable().selectColumn(p -> ProDsl.of(p).nullValue(HanaTypes.tinyint())).toList().get(0));
            assertThrows(RuntimeException.class, () -> client.updatable(TestProduct.class).asTable(fixture.name()).setColumns(p -> p.amount().set(new BigDecimal("256"))).where(p -> p.id().eq(1)).executeRows());
            NativeDecimalContract.equalValue(new BigDecimal("255"), rows.cloneQueryable().singleNotNull().getAmount());
        }
    }
}
