package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.hana.json.HanaJsonSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaJsonDocumentIT {
    @Test
    void nativeHelperAcceptsOneValueIncludingScalarsAndNestedEmptyArrays() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("JSON_DOCUMENT");
             var connection = HanaTestDatabase.connect(); var statement = connection.createStatement()) {
            statement.execute(HanaJsonSupport.createDocumentFunctionSql(schema.name()));
            String function = HanaJsonSupport.documentFunctionName(schema.name());
            for (String doc : Arrays.asList(null, "1", "true", "null", "\"中😀\"", "{}", "[]", "[[]]", "[[1],[2]]", "[1,null]")) {
                assertEquals(doc, read(connection, function, doc, "ANY"));
            }
            for (String invalid : List.of("", " \t\r\n", "1,2", "1,", "invalid json")) {
                assertThrows(SQLException.class, () -> read(connection, function, invalid, "ANY"));
            }
        }
    }

    @Test
    void kindFilteringDistinguishesSqlNullJsonNullAndContainerMismatches() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("JSON_KIND");
             var connection = HanaTestDatabase.connect(); var statement = connection.createStatement()) {
            statement.execute(HanaJsonSupport.createDocumentFunctionSql(schema.name()));
            String function = HanaJsonSupport.documentFunctionName(schema.name());
            assertEquals(" \t{}", read(connection, function, " \t{}", "OBJECT"));
            assertEquals("[]", read(connection, function, "[]", "ARRAY"));
            assertEquals("null", read(connection, function, "null", "SCALAR"));
            assertEquals("\"text\"", read(connection, function, "\"text\"", "SCALAR"));
            assertNull(read(connection, function, "[]", "OBJECT"));
            assertNull(read(connection, function, "{}", "ARRAY"));
            assertNull(read(connection, function, "{}", "SCALAR"));
            assertNull(read(connection, function, "null", "OBJECT"));
            assertNull(read(connection, function, null, "SCALAR"));
            assertThrows(SQLException.class, () -> read(connection, function, "invalid", "ARRAY"));
            assertThrows(SQLException.class, () -> read(connection, function, "{}", "WRONG"));
            assertThrows(SQLException.class, () -> read(connection, function, "{}", null));
        }
    }

    private static String read(Connection connection, String function, String doc, String kind) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT " + function + "(?,?) FROM DUMMY")) {
            statement.setString(1, doc);
            statement.setString(2, kind);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }
}
