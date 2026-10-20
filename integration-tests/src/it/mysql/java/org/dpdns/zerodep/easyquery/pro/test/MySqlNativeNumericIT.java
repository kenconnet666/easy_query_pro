package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

class MySqlNativeNumericIT {
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
