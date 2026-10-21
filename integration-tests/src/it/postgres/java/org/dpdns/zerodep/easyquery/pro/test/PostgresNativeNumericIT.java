package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.Statement;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresNativeNumericIT {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void basicNumericWidthsSurviveTextAndBinary(boolean binary) throws SQLException {
        PGSimpleDataSource source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(binary ? -1 : 0);
        try (PostgresTestDatabase.Schema schema = PostgresTestDatabase.createSchema(source, "eqp_basic_numeric_");
             Statement ddl = schema.connection().createStatement()) {
            DefaultEasyEntityQuery client = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
            ddl.execute("CREATE TABLE " + schema.qualified("basic_numbers") + " ("
                    + BasicNumericContract.columns(client, SqlDialect.POSTGRESQL) + ")");
            BasicNumericContract.verify(client, "basic_numbers");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeScalesAndUnconstrainedNumbersSurviveTextAndBinary(boolean binary) throws SQLException {
        PGSimpleDataSource source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(binary ? -1 : 0);
        source.setBinaryTransferEnable("numeric");
        try (PostgresTestDatabase.Schema schema = PostgresTestDatabase.createSchema(source, "eqp_native_numeric_"); Statement ddl = schema.connection().createStatement()) {
            DefaultEasyEntityQuery client = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
            List<NumericType<BigDecimal>> types = Arrays.asList(PgSqlTypes.numeric(2, -3), PgSqlTypes.numeric(3, 5), PgSqlTypes.numeric(1000, 1000), PgSqlTypes.numeric());
            List<List<BigDecimal>> values = Arrays.asList(Arrays.asList(new BigDecimal("99000"), new BigDecimal("-99000"), BigDecimal.ZERO),
                    Arrays.asList(new BigDecimal("0.00999"), new BigDecimal("-0.00999"), new BigDecimal("0.00001")),
                    Arrays.asList(new BigDecimal("0." + String.join("", Collections.nCopies(1000, "9"))), new BigDecimal("1E-1000"), BigDecimal.ZERO),
                    Arrays.asList(new BigDecimal(String.join("", Collections.nCopies(1001, "9"))), new BigDecimal("1E131071"), new BigDecimal("1E-16383"), BigDecimal.ZERO));
            for (int i = 0; i < types.size(); i++) {
                String table = "numbers_" + i;
                ddl.execute("CREATE TABLE " + schema.qualified(table) + " (id INTEGER PRIMARY KEY, name VARCHAR(20), amount " + types.get(i).ddlType(SqlDialect.POSTGRESQL) + ")");
                NativeDecimalContract.verify(client, table, types.get(i), values.get(i));
            }
            NativeDecimalContract.nativeRounding(client, "numbers_0", PgSqlTypes.numeric(2, -3), "-98500", "-99000");
            NativeDecimalContract.nativeRounding(client, "numbers_1", PgSqlTypes.numeric(3, 5), "0.009994", "0.00999");
            assertThrows(RuntimeException.class, () -> client.getEasyQueryClient().sqlQuery("SELECT 'NaN'::numeric", BigDecimal.class));
            assertThrows(RuntimeException.class, () -> client.getEasyQueryClient().sqlQuery("SELECT 'Infinity'::numeric", BigDecimal.class));
        }
    }
}
