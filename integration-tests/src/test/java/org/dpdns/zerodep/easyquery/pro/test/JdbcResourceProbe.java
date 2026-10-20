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
            for (var result : results) assertTrue(result.isClosed(), "A JDBC result remained open");
        }
        synchronized (statements) {
            for (var statement : statements) assertTrue(statement.isClosed(), "A JDBC statement remained open");
        }
    }

    private synchronized Object wrap(Object delegate, Class<?> contract) {
        return wrappers.computeIfAbsent(delegate, ignored -> {
            if (delegate instanceof ResultSet result) results.add(result);
            else if (delegate instanceof Statement statement) statements.add(statement);
            return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
                if (delegate instanceof Statement statement && (method.getName().equals("executeQuery") || method.getName().equals("execute"))) {
                    executions.add(new Execution(statement.getFetchSize(), statement.getConnection().getAutoCommit(), statement.getResultSetType()));
                }
                try {
                    var result = method.invoke(delegate, args);
                    if (result instanceof ResultSet rows) return wrap(rows, ResultSet.class);
                    if (result instanceof CallableStatement statement) return wrap(statement, CallableStatement.class);
                    if (result instanceof PreparedStatement statement) return wrap(statement, PreparedStatement.class);
                    if (result instanceof Statement statement) return wrap(statement, Statement.class);
                    if (result instanceof Connection connection) return wrap(connection, Connection.class);
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

    record Execution(int fetchSize, boolean autoCommit, int resultSetType) {
    }
}
