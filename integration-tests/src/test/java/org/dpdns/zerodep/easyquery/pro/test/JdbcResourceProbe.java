package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.extension.listener.JdbcExecuteAfterArg;
import com.easy.query.core.basic.extension.listener.JdbcExecuteBeforeArg;
import com.easy.query.core.basic.extension.listener.JdbcExecutorListener;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 观测当前测试实际取得的 JDBC 句柄，不替代连接池的资源所有权，也不拦截驱动内部网络 fetch。
 */
final class JdbcResourceProbe implements JdbcExecutorListener {
    private final Map<Object, Object> wrappers = new IdentityHashMap<>();
    private final List<Statement> statements = Collections.synchronizedList(new ArrayList<>());
    private final List<ResultSet> results = Collections.synchronizedList(new ArrayList<>());
    private final List<Execution> executions = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger sqlExecutions = new AtomicInteger();
    private final DataSource source;

    JdbcResourceProbe(DataSource delegate) {
        source = (DataSource) wrap(delegate, DataSource.class);
    }

    DataSource dataSource() {
        return source;
    }

    int sqlExecutions() {
        return sqlExecutions.get();
    }

    int jdbcExecutions() {
        return executions.size();
    }

    Execution execution(int index) {
        return executions.get(index);
    }

    int statementCount() {
        return statements.size();
    }

    int resultCount() {
        return results.size();
    }

    void assertReleased() throws SQLException {
        synchronized (results) {
            for (ResultSet result : results) assertTrue(result.isClosed(), "A JDBC result remained open");
        }
        synchronized (statements) {
            for (Statement statement : statements) assertTrue(statement.isClosed(), "A JDBC statement remained open");
        }
    }

    private synchronized Object wrap(Object delegate, Class<?> contract) {
        return wrappers.computeIfAbsent(delegate, ignored -> {
            if (delegate instanceof ResultSet) results.add(((ResultSet) delegate));
            else if (delegate instanceof Statement) statements.add(((Statement) delegate));
            return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
                if (delegate instanceof Statement && (method.getName().equals("executeQuery") || method.getName().equals("execute"))) {
                    Statement statement = (Statement) delegate;
                    executions.add(new Execution(statement.getFetchSize(), statement.getConnection().getAutoCommit(), statement.getResultSetType()));
                }
                try {
                    Object result = method.invoke(delegate, args);
                    if (result instanceof ResultSet) return wrap(((ResultSet) result), ResultSet.class);
                    if (result instanceof CallableStatement) return wrap(((CallableStatement) result), CallableStatement.class);
                    if (result instanceof PreparedStatement) return wrap(((PreparedStatement) result), PreparedStatement.class);
                    if (result instanceof Statement) return wrap(((Statement) result), Statement.class);
                    if (result instanceof Connection) return wrap(((Connection) result), Connection.class);
                    return result;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        });
    }

    @Override
    public boolean enable() {
        return true;
    }

    @Override
    public void onExecuteBefore(JdbcExecuteBeforeArg argument) {
        sqlExecutions.incrementAndGet();
    }

    @Override
    public void onExecuteAfter(JdbcExecuteAfterArg argument) {
    }

    static final class Execution {
        private final int fetchSize;
        private final boolean autoCommit;
        private final int resultSetType;

        Execution(int fetchSize, boolean autoCommit, int resultSetType) {
            this.fetchSize = fetchSize;
            this.autoCommit = autoCommit;
            this.resultSetType = resultSetType;
        }

        public int fetchSize() {
            return fetchSize;
        }

        public boolean autoCommit() {
            return autoCommit;
        }

        public int resultSetType() {
            return resultSetType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Execution)) return false;
            Execution other = (Execution) o;
            return fetchSize == other.fetchSize
                    && autoCommit == other.autoCommit
                    && resultSetType == other.resultSetType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(fetchSize, autoCommit, resultSetType);
        }

        @Override
        public String toString() {
            return "Execution[fetchSize=" + fetchSize + ", autoCommit=" + autoCommit + ", resultSetType=" + resultSetType + "]";
        }
    }
}
