package org.dpdns.zerodep.easyquery.pro.test;

import com.sap.db.jdbcext.HanaDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

final class HanaTestDatabase {
    private HanaTestDatabase() {
    }

    static DataSource dataSource() throws SQLException {
        var dataSource = new HanaDataSource();
        dataSource.setUrl(TestEnvironment.required("EQP_HANA_JDBC_URL"));
        dataSource.setUser(TestEnvironment.required("EQP_HANA_USER"));
        dataSource.setPassword(TestEnvironment.required("EQP_HANA_PASSWORD"));
        dataSource.setLoginTimeout(10);
        return dataSource;
    }

    static Connection connect() throws SQLException {
        return dataSource().getConnection();
    }

    static String uniqueName(String prefix) {
        return "EQP_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
