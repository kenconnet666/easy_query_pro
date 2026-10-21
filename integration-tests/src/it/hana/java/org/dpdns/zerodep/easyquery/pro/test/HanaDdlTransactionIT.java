package org.dpdns.zerodep.easyquery.pro.test;

import com.sap.db.jdbc.ConnectionSapDB;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

class HanaDdlTransactionIT {
    @Test
    void explicitDdlTransactionModeAllowsRollbackAndTracksSessionState() throws SQLException {
        String table = HanaTestDatabase.uniqueName("DDL_ROLLBACK");
        try (Connection connection = HanaTestDatabase.connect(); Statement statement = connection.createStatement()) {
            ConnectionSapDB hana = connection.unwrap(ConnectionSapDB.class);
            boolean previous = hana.isDDLAutoCommit();
            connection.setAutoCommit(false);
            try {
                statement.execute("SET TRANSACTION AUTOCOMMIT DDL OFF");
                assertFalse(hana.isDDLAutoCommit());
                statement.execute("CREATE COLUMN TABLE \"" + table + "\" (\"id\" INTEGER)");
                connection.rollback();
                try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM SYS.TABLES WHERE SCHEMA_NAME=CURRENT_SCHEMA AND TABLE_NAME='" + table + "'")) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }
            } finally {
                connection.rollback();
                statement.execute("SET TRANSACTION AUTOCOMMIT DDL " + (previous ? "ON" : "OFF"));
                connection.setAutoCommit(true);
            }
            assertEquals(previous, hana.isDDLAutoCommit());
        }
    }
}
