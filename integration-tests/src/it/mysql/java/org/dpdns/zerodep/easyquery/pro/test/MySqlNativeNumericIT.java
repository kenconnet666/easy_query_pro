package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MySqlNativeNumericIT {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void floatBindingRequiresExactWideningInClientPreparedMode(boolean serverPrepared) throws SQLException {
        var source = MySqlTestDatabase.dataSource();
        source.setUseServerPrepStmts(serverPrepared);
        source.setEmulateUnsupportedPstmts(false);
        source.setSessionVariables("sql_mode='STRICT_ALL_TABLES'");
        try (var fixture = MySqlTestDatabase.createTable(source, "eqp_float_binding_", "id INTEGER PRIMARY KEY, number_value FLOAT");
             var connection = source.getConnection()) {
            String insert = "INSERT INTO `" + fixture.name() + "` (id,number_value) VALUES (?,?)";
            try (var statement = connection.prepareStatement(insert)) {
                statement.setInt(1, 1);
                statement.setFloat(2, Float.MAX_VALUE);
                if (serverPrepared) assertEquals(1, statement.executeUpdate());
                else assertEquals(1264, assertThrows(SQLException.class, statement::executeUpdate).getErrorCode());
            }
            try (var statement = connection.prepareStatement(insert)) {
                statement.setInt(1, 2);
                statement.setDouble(2, (double) Float.MAX_VALUE);
                assertEquals(1, statement.executeUpdate());
            }
            try (var statement = connection.prepareStatement("SELECT number_value,CAST(number_value AS DOUBLE) FROM `"
                    + fixture.name() + "` WHERE id=?")) {
                statement.setInt(1, 2);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals((double) Float.MAX_VALUE, result.getDouble(2), "Stored FLOAT remains exact");
                    if (serverPrepared) assertEquals(Float.MAX_VALUE, result.getFloat(1));
                    else
                        assertEquals(3.40282E38f, result.getFloat(1), "MySQL text results expose only six significant FLOAT digits");
                    assertFalse(result.next());
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void basicNumericWidthsIgnoreRealSqlMode(boolean serverPrepared, boolean realAsFloat) throws SQLException {
        var source = MySqlTestDatabase.dataSource();
        source.setUseServerPrepStmts(serverPrepared);
        source.setEmulateUnsupportedPstmts(false);
        source.setRewriteBatchedStatements(true);
        source.setSessionVariables("sql_mode='STRICT_ALL_TABLES" + (realAsFloat ? ",REAL_AS_FLOAT" : "") + "'");
        var client = TestClients.configured(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        try (var fixture = MySqlTestDatabase.createTable(source, "eqp_basic_numeric_",
                BasicNumericContract.columns(client, SqlDialect.MYSQL))) {
            if (serverPrepared) BasicNumericContract.verify(client, fixture.name());
            else BasicNumericContract.verifyMySqlTextResults(client, fixture.name());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullDecimalCapacitySurvivesBothPreparedStatementModes(boolean serverPrepared) throws SQLException {
        var source = MySqlTestDatabase.dataSource();
        source.setUseServerPrepStmts(serverPrepared);
        source.setRewriteBatchedStatements(true);
        source.setSessionVariables("sql_mode='STRICT_ALL_TABLES'");
        for (int scale : new int[]{0, 30}) {
            var type = MySqlTypes.decimal(65, scale);
            var max = new BigDecimal("9".repeat(65)).movePointLeft(scale);
            try (var fixture = MySqlTestDatabase.createTable(source, "eqp_native_decimal_", "id INTEGER PRIMARY KEY, name VARCHAR(20), amount " + type.ddlType(SqlDialect.MYSQL))) {
                var client = TestClients.configured(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
                NativeDecimalContract.verify(client, fixture.name(), type, List.of(max, max.negate(), BigDecimal.ZERO, BigDecimal.ONE.scaleByPowerOfTen(-scale)));
                NativeDecimalContract.nativeRounding(client, fixture.name(), MySqlTypes.decimal(4, 1), "-1.25", "-1.3");
            }
        }
    }
}
