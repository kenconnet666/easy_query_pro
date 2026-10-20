package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.executor.internal.props.BasicJdbcProperty;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.parameter.EasyConstSQLParameter;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayTypeHandler;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayValue;
import org.junit.jupiter.api.Test;
import org.postgresql.jdbc.PgArray;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PgArrayLifecycleTest {
    @Test
    void readFailureFreesArrayAndKeepsCleanupFailureSuppressed() throws SQLException {
        var array = new FailingArray();
        var result = (StreamResultSet) Proxy.newProxyInstance(StreamResultSet.class.getClassLoader(), new Class<?>[]{StreamResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getObject")) return array;
                    throw new AssertionError(method.getName());
                });
        var error = assertThrows(SQLException.class, () -> new PgArrayTypeHandler().getValue(new BasicJdbcProperty(0, PgArrayValue.class), result));
        assertSame(array.failure, error);
        assertEquals(1, array.frees);
        assertArrayEquals(new Throwable[]{array.cleanup}, error.getSuppressed());
    }

    @Test
    void bindingFailureFreesArrayAndKeepsCleanupFailureSuppressed() throws SQLException {
        var array = new FailingArray();
        var connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("createArrayOf")) return array;
                    throw new AssertionError(method.getName());
                });
        var statement = (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    case "setArray" -> throw array.failure;
                    default -> throw new AssertionError(method.getName());
                });
        var parameter = new EasyParameter(statement, List.of(new EasyConstSQLParameter(null, null, PgSqlTypes.array(Integer.class).value(List.of(1)))));
        parameter.setIndex(0);
        var error = assertThrows(SQLException.class, () -> new PgArrayTypeHandler().setParameter(parameter));
        assertSame(array.failure, error);
        assertEquals(1, array.frees);
        assertArrayEquals(new Throwable[]{array.cleanup}, error.getSuppressed());
    }

    private static final class FailingArray extends PgArray {
        private final SQLException failure = new SQLException("Primary failure");
        private final SQLException cleanup = new SQLException("Cleanup failure");
        private int frees;

        private FailingArray() throws SQLException {
            super(null, 0, "{1}");
        }

        @Override
        public String getBaseTypeName() {
            return "int4";
        }

        @Override
        public ResultSet getResultSet() throws SQLException {
            throw failure;
        }

        @Override
        public void free() throws SQLException {
            frees++;
            super.free();
            throw cleanup;
        }
    }
}
