package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

class MySqlJsonPathIT {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keysRemainDataWithNoBackslashEscapesAndEitherPreparationMode(boolean serverPrepared) throws SQLException {
        var dataSource = new MysqlDataSource();
        dataSource.setURL(TestEnvironment.required("EQP_MYSQL_JDBC_URL"));
        dataSource.setUser(TestEnvironment.required("EQP_MYSQL_USER"));
        dataSource.setPassword(TestEnvironment.required("EQP_MYSQL_PASSWORD"));
        dataSource.setConnectTimeout(10_000);
        dataSource.setSocketTimeout(30_000);
        dataSource.setUseServerPrepStmts(serverPrepared);
        dataSource.setSessionVariables("sql_mode='NO_BACKSLASH_ESCAPES'");
        JsonPathDslContract.verify(dataSource, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
    }
}
