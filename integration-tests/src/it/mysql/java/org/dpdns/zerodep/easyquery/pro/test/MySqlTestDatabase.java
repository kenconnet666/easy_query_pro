package org.dpdns.zerodep.easyquery.pro.test;

import com.mysql.cj.jdbc.MysqlDataSource;

import java.sql.SQLException;

final class MySqlTestDatabase {
    private MySqlTestDatabase() {
    }

    static MysqlDataSource dataSource() throws SQLException {
        var source = new MysqlDataSource();
        source.setURL(TestEnvironment.required("EQP_MYSQL_JDBC_URL"));
        source.setUser(TestEnvironment.required("EQP_MYSQL_USER"));
        source.setPassword(TestEnvironment.required("EQP_MYSQL_PASSWORD"));
        source.setConnectTimeout(10_000);
        source.setSocketTimeout(30_000);
        return source;
    }
}
