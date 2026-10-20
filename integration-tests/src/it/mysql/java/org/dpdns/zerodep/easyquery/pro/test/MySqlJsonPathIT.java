package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

class MySqlJsonPathIT {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keysRemainDataWithNoBackslashEscapesAndEitherPreparationMode(boolean serverPrepared) throws SQLException {
        var dataSource = MySqlTestDatabase.dataSource();
        dataSource.setUseServerPrepStmts(serverPrepared);
        dataSource.setSessionVariables("sql_mode='NO_BACKSLASH_ESCAPES'");
        JsonPathDslContract.verify(dataSource, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
    }
}
