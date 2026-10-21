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

import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresNativeNumericIT {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void basicNumericWidthsSurviveTextAndBinary(boolean binary) throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(binary ? -1 : 0);
        try (var schema = PostgresTestDatabase.createSchema(source, "eqp_basic_numeric_");
             var ddl = schema.connection().createStatement()) {
            var client = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
            ddl.execute("CREATE TABLE " + schema.qualified("basic_numbers") + " ("
                    + BasicNumericContract.columns(client, SqlDialect.POSTGRESQL) + ")");
            BasicNumericContract.verify(client, "basic_numbers");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeScalesAndUnconstrainedNumbersSurviveTextAndBinary(boolean binary) throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(binary ? -1 : 0);
        source.setBinaryTransferEnable("numeric");
        try (var schema = PostgresTestDatabase.createSchema(source, "eqp_native_numeric_"); var ddl = schema.connection().createStatement()) {
            var client = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
            var types = List.of(PgSqlTypes.numeric(2, -3), PgSqlTypes.numeric(3, 5), PgSqlTypes.numeric(1000, 1000), PgSqlTypes.numeric());
            var values = List.of(List.of(new BigDecimal("99000"), new BigDecimal("-99000"), BigDecimal.ZERO),
                    List.of(new BigDecimal("0.00999"), new BigDecimal("-0.00999"), new BigDecimal("0.00001")),
                    List.of(new BigDecimal("0." + "9".repeat(1000)), new BigDecimal("1E-1000"), BigDecimal.ZERO),
                    List.of(new BigDecimal("9".repeat(1001)), new BigDecimal("1E131071"), new BigDecimal("1E-16383"), BigDecimal.ZERO));
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
