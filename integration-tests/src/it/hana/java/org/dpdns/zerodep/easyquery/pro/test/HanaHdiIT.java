package org.dpdns.zerodep.easyquery.pro.test;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HanaHdiIT {
    @Test
    void deploysAndQueriesARealParameterizedCalculationView() throws SQLException, IOException {
        // HDI 容器名称比普通带引号的 schema 更严格，只接受大写字母、数字和下划线。
        String container = HanaTestDatabase.uniqueName("HDI").toUpperCase(Locale.ROOT);
        try (var connection = HanaTestDatabase.connect(); var setup = connection.createStatement()) {
            setup.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_PRIVILEGES LIKE _SYS_DI.TT_API_PRIVILEGES");
            setup.execute("INSERT INTO #EQP_HDI_PRIVILEGES SELECT PRIVILEGE_NAME, OBJECT_NAME, PRINCIPAL_SCHEMA_NAME,"
                    + " CURRENT_USER FROM _SYS_DI.T_DEFAULT_CONTAINER_ADMIN_PRIVILEGES");
            setup.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_DROP_PARAMETERS LIKE _SYS_DI.TT_PARAMETERS");
            setup.execute("INSERT INTO #EQP_HDI_DROP_PARAMETERS SELECT 'ignore_work','true' FROM DUMMY"
                    + " UNION ALL SELECT 'ignore_deployed','true' FROM DUMMY");
            invoke(connection, "CALL _SYS_DI.CREATE_CONTAINER('" + container
                    + "', _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
            try {
                invoke(connection, "CALL _SYS_DI.GRANT_CONTAINER_API_PRIVILEGES('" + container
                        + "', #EQP_HDI_PRIVILEGES, _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
                invoke(connection, "CALL \"" + container + "#DI\".CONFIGURE_LIBRARIES(_SYS_DI.T_DEFAULT_LIBRARIES,"
                        + " _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
                deploy(connection, container);
                grantSchemaAccess(connection, container);
                verifyCalculationView(connection, container);
            } finally {
                invoke(connection, "CALL _SYS_DI.DROP_CONTAINER('" + container
                        + "', #EQP_HDI_DROP_PARAMETERS, ?, ?, ?)");
            }
        }
    }

    private static void deploy(Connection connection, String container) throws SQLException, IOException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_FILES LIKE _SYS_DI.TT_FILESFOLDERS_CONTENT");
            statement.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_PATHS LIKE _SYS_DI.TT_FILESFOLDERS");
        }
        try (var files = connection.prepareStatement("INSERT INTO #EQP_HDI_FILES (PATH,CONTENT) VALUES (?,?)");
             var paths = connection.prepareStatement("INSERT INTO #EQP_HDI_PATHS (PATH) VALUES (?)")) {
            for (String path : List.of(".hdiconfig", "EQP_SOURCE.hdbtable", "EQP_CALC.hdbcalculationview")) {
                try (var resource = HanaHdiIT.class.getResourceAsStream("/hana/hdi/" + path)) {
                    assertNotNull(resource, path);
                    files.setString(1, path);
                    files.setBytes(2, resource.readAllBytes());
                    files.executeUpdate();
                }
                paths.setString(1, path);
                paths.executeUpdate();
            }
        }
        invoke(connection, "CALL \"" + container + "#DI\".WRITE(#EQP_HDI_FILES, _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
        invoke(connection, "CALL \"" + container + "#DI\".MAKE(#EQP_HDI_PATHS, _SYS_DI.T_NO_FILESFOLDERS,"
                + " _SYS_DI.T_NO_FILESFOLDERS_PARAMETERS, _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
    }

    private static void grantSchemaAccess(Connection connection, String container) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_SCHEMA_PRIVILEGES LIKE _SYS_DI.TT_SCHEMA_PRIVILEGES");
            statement.execute("INSERT INTO #EQP_HDI_SCHEMA_PRIVILEGES SELECT 'SELECT','',CURRENT_USER FROM DUMMY");
            statement.execute("INSERT INTO #EQP_HDI_SCHEMA_PRIVILEGES SELECT 'INSERT','',CURRENT_USER FROM DUMMY");
        }
        invoke(connection, "CALL _SYS_DI.GRANT_CONTAINER_SCHEMA_PRIVILEGES('" + container
                + "', #EQP_HDI_SCHEMA_PRIVILEGES, _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
    }

    private static void verifyCalculationView(Connection connection, String container) throws SQLException {
        String schema = '"' + container + '"';
        try (var metadata = connection.prepareStatement("SELECT VIEW_TYPE FROM SYS.VIEWS WHERE SCHEMA_NAME=? AND VIEW_NAME='EQP_CALC'")) {
            metadata.setString(1, container);
            try (var result = metadata.executeQuery()) {
                assertTrue(result.next());
                assertEquals("CALC", result.getString(1));
            }
        }
        try (var insert = connection.prepareStatement("INSERT INTO " + schema + ".\"EQP_SOURCE\" VALUES (?,?,?)")) {
            for (int id = 1; id <= 2; id++) {
                insert.setInt(1, id);
                insert.setString(2, id == 1 ? "O'Reilly 中文" : "other");
                insert.setBigDecimal(3, new BigDecimal("123.4500"));
                insert.executeUpdate();
            }
        }
        try (var select = connection.prepareStatement("SELECT \"ID\",\"NAME\",\"AMOUNT\" FROM " + schema
                + ".\"EQP_CALC\" (PLACEHOLDER.\"$$P_NAME$$\" => ?)")) {
            select.setString(1, "O'Reilly 中文");
            try (var result = select.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertEquals("O'Reilly 中文", result.getString(2));
                assertEquals(new BigDecimal("123.4500"), result.getBigDecimal(3));
                assertFalse(result.next());
            }
            select.setString(1, "' OR 1=1 --");
            try (var result = select.executeQuery()) {
                assertFalse(result.next());
            }
        }
    }

    private static void invoke(Connection connection, String call) throws SQLException {
        // HDI 的 SQL 调用成功不代表部署成功，必须检查过程返回码和诊断消息。
        var diagnostics = new ArrayList<String>();
        // 用顶层 CALL 传入临时表；包装成 SQLScript DO 会限制临时表参数的使用。
        try (var statement = connection.prepareCall(call)) {
            statement.setQueryTimeout(60);
            statement.registerOutParameter(1, Types.INTEGER);
            statement.registerOutParameter(2, Types.BIGINT);
            boolean hasResult = statement.execute();
            while (hasResult || statement.getUpdateCount() != -1) {
                if (hasResult) {
                    try (var result = statement.getResultSet()) {
                        while (result.next()) {
                            diagnostics.add(result.getString("SEVERITY") + " " + result.getString("MESSAGE_CODE")
                                    + ": " + result.getString("MESSAGE") + " (" + result.getString("PATH") + ")");
                        }
                    }
                }
                hasResult = statement.getMoreResults();
            }
            assertEquals(0, statement.getInt(1), () -> "HDI failed: " + String.join("; ", diagnostics));
        }
    }
}
