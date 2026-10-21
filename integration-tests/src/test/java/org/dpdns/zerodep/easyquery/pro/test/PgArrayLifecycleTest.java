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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PgArrayLifecycleTest {
    @Test
    void readFailureFreesArrayAndKeepsCleanupFailureSuppressed() throws SQLException {
        PgArrayLifecycleTest.FailingArray array = new FailingArray();
        StreamResultSet result = (StreamResultSet) Proxy.newProxyInstance(StreamResultSet.class.getClassLoader(), new Class<?>[]{StreamResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getObject")) return array;
                    throw new AssertionError(method.getName());
                });
        SQLException error = assertThrows(SQLException.class, () -> new PgArrayTypeHandler().getValue(new BasicJdbcProperty(0, PgArrayValue.class), result));
        assertSame(array.failure, error);
        assertEquals(1, array.frees);
        assertArrayEquals(new Throwable[]{array.cleanup}, error.getSuppressed());
    }

    @Test
    void bindingFailureFreesArrayAndKeepsCleanupFailureSuppressed() throws SQLException {
        PgArrayLifecycleTest.FailingArray array = new FailingArray();
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("createArrayOf")) return array;
                    throw new AssertionError(method.getName());
                });
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getConnection": return connection;
                        case "setArray": throw array.failure;
                        default: throw new AssertionError(method.getName());
                    }
                });
        EasyParameter parameter = new EasyParameter(statement, Arrays.asList(new EasyConstSQLParameter(null, null, PgSqlTypes.array(Integer.class).value(Arrays.asList(1)))));
        parameter.setIndex(0);
        SQLException error = assertThrows(SQLException.class, () -> new PgArrayTypeHandler().setParameter(parameter));
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
