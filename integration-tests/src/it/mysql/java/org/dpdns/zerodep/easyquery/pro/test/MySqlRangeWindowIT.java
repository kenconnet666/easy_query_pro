package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import com.mysql.cj.jdbc.MysqlDataSource;

class MySqlRangeWindowIT {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rangeOffsetsBindWithBothPreparationModes(boolean serverPrepared) throws SQLException {
        MysqlDataSource source = MySqlTestDatabase.dataSource();
        source.setUseServerPrepStmts(serverPrepared);
        source.setEmulateUnsupportedPstmts(false);
        RangeWindowContract.verify(TestClients.configured(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure));
    }
}
