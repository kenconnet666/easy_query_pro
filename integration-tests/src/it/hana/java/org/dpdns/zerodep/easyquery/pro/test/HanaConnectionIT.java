package org.dpdns.zerodep.easyquery.pro.test;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HanaConnectionIT {
    @Test
    void jdbcDriverConnectsToTheConfiguredHana() throws SQLException {
        String url = TestEnvironment.required("EQP_HANA_JDBC_URL");
        String user = TestEnvironment.required("EQP_HANA_USER");
        String password = TestEnvironment.required("EQP_HANA_PASSWORD");
        // This checks JDBC connectivity only; the HANA adapter is not implemented yet.
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            try (var result = statement.executeQuery("SELECT CURRENT_USER, CURRENT_SCHEMA FROM DUMMY")) {
                assertTrue(result.next());
                assertNotNull(result.getString(1));
                assertNotNull(result.getString(2));
                assertFalse(result.next());
            }
        }
    }
}
