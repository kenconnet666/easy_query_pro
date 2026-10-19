package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class MySqlConnectionIT {
    @Test
    void upstreamAdapterWorksWithTheConfiguredMysql() throws SQLException {
        var dataSource = new MysqlDataSource();
        dataSource.setURL(TestEnvironment.required("EQP_MYSQL_JDBC_URL"));
        dataSource.setUser(TestEnvironment.required("EQP_MYSQL_USER"));
        dataSource.setPassword(TestEnvironment.required("EQP_MYSQL_PASSWORD"));
        dataSource.setConnectTimeout(10_000);
        dataSource.setSocketTimeout(30_000);
        JdbcContract.verify(dataSource, new MySQLDatabaseConfiguration());
    }
}
