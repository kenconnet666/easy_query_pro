package org.dpdns.zerodep.easyquery.pro.test;

import org.junit.jupiter.api.Test;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseInfo;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HanaConnectionIT {
    @Test
    void jdbcDriverConnectsToTheConfiguredHana() throws SQLException {
        try (Connection connection = HanaTestDatabase.connect();
             Statement statement = connection.createStatement()) {
            HanaDatabaseInfo info = HanaDatabaseInfo.inspect(connection);
            System.out.println("HANA database version: " + info.productVersion());
            statement.setQueryTimeout(15);
            try (ResultSet result = statement.executeQuery("SELECT DATABASE_NAME FROM SYS.M_DATABASE")) {
                assertTrue(result.next());
                assertNotEquals("SYSTEMDB", result.getString(1), "ORM contracts must run against a HANA tenant database");
            }
            try (ResultSet result = statement.executeQuery("SELECT CURRENT_USER, CURRENT_SCHEMA FROM DUMMY")) {
                assertTrue(result.next());
                assertNotNull(result.getString(1));
                assertNotNull(result.getString(2));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void jdbcBindsUnicodeAndDecimalsAndRollsBack() throws SQLException {
        String table = '"' + HanaTestDatabase.uniqueName("JDBC") + '"';
        try (Connection connection = HanaTestDatabase.connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE COLUMN TABLE " + table
                    + " (\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(255), \"amount\" DECIMAL(19,4))");
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table + " VALUES (?, ?, ?)")) {
                    insert.setInt(1, 1);
                    insert.setString(2, "O'Reilly 中文");
                    insert.setBigDecimal(3, new BigDecimal("123.4500"));
                    assertEquals(1, insert.executeUpdate());
                    connection.commit();
                    insert.setInt(1, 2);
                    assertEquals(1, insert.executeUpdate());
                    connection.rollback();
                }
                try (ResultSet result = statement.executeQuery("SELECT * FROM " + table)) {
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

    @Test
    void hanaAdapterExecutesTheSharedCrudAndTransactionContract() throws SQLException {
        JdbcContract.verify(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), CommonTypes.unicodeText(255).ddlType(SqlDialect.HANA));
    }
}
