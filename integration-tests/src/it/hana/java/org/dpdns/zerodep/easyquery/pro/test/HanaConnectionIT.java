package org.dpdns.zerodep.easyquery.pro.test;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HanaConnectionIT {
    @Test
    void jdbcDriverConnectsToTheConfiguredHana() throws SQLException {
        // This checks JDBC connectivity only; the HANA adapter is not implemented yet.
        try (var connection = connect();
             var statement = connection.createStatement()) {
            System.out.println("HANA database version: " + connection.getMetaData().getDatabaseProductVersion());
            statement.setQueryTimeout(15);
            try (var result = statement.executeQuery("SELECT CURRENT_USER, CURRENT_SCHEMA FROM DUMMY")) {
                assertTrue(result.next());
                assertNotNull(result.getString(1));
                assertNotNull(result.getString(2));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void jdbcBindsUnicodeAndDecimalsAndRollsBack() throws SQLException {
        String table = "\"EQP_JDBC_" + UUID.randomUUID().toString().replace("-", "") + "\"";
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE COLUMN TABLE " + table
                    + " (\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(255), \"amount\" DECIMAL(19,4))");
            try {
                connection.setAutoCommit(false);
                try (var insert = connection.prepareStatement("INSERT INTO " + table + " VALUES (?, ?, ?)")) {
                    insert.setInt(1, 1);
                    insert.setString(2, "O'Reilly 中文");
                    insert.setBigDecimal(3, new BigDecimal("123.4500"));
                    assertEquals(1, insert.executeUpdate());
                    connection.commit();
                    insert.setInt(1, 2);
                    assertEquals(1, insert.executeUpdate());
                    connection.rollback();
                }
                try (var result = statement.executeQuery("SELECT * FROM " + table)) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                    assertEquals("O'Reilly 中文", result.getString(2));
                    assertEquals(new BigDecimal("123.4500"), result.getBigDecimal(3));
                    assertFalse(result.next());
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
                statement.execute("DROP TABLE " + table);
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(TestEnvironment.required("EQP_HANA_JDBC_URL"),
                TestEnvironment.required("EQP_HANA_USER"), TestEnvironment.required("EQP_HANA_PASSWORD"));
    }
}
