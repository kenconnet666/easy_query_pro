package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.conn.EasyConnection;
import com.easy.query.core.basic.jdbc.executor.ExecutorContext;
import com.easy.query.core.enums.ExecuteMethodEnum;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.jdbc.PgJdbcSQLExecutor;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaJdbcSQLExecutor;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class QueryResultCleanupTest {
    static Stream<Arguments> failures() {
        return Stream.of(false, true).flatMap(hana -> Stream.of(Cleanup.values()).map(mode -> Arguments.of(hana, mode)));
    }

    @ParameterizedTest(name = "hana={0}, cleanup={1}")
    @MethodSource("failures")
    void wrapperFailureKeepsItsIdentityWhenStatementCleanupAlsoFails(boolean hana, Cleanup mode) {
        Throwable original = mode == Cleanup.SAME ? new IllegalStateException("fixture metadata failure")
                : new SQLException("fixture metadata failure");
        Throwable cleanup = switch (mode) {
            case RUNTIME -> new IllegalStateException("fixture statement close failure");
            case ERROR -> new AssertionError("fixture statement close failure");
            case SAME -> original;
        };
        var resultCloses = new AtomicInteger();
        var statementCloses = new AtomicInteger();
        var result = proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
            case "next" -> true;
            case "getMetaData" -> throw original;
            case "close" -> {
                resultCloses.incrementAndGet();
                yield null;
            }
            default -> throw new AssertionError(method.getName());
        });
        var statement = proxy(PreparedStatement.class, (ignored, method, args) -> switch (method.getName()) {
            case "executeQuery", "getResultSet" -> result;
            case "setFetchSize" -> null;
            case "close" -> {
                statementCloses.incrementAndGet();
                throw cleanup;
            }
            default -> throw new AssertionError(method.getName());
        });
        var connection = proxy(Connection.class, (ignored, method, args) -> {
            if (method.getName().equals("prepareStatement")) return statement;
            throw new AssertionError(method.getName());
        });
        var easyConnection = proxy(EasyConnection.class, (ignored, method, args) -> {
            if (method.getName().equals("getConnection")) return connection;
            if (method.getName().equals("getDataSourceName")) return "fixture";
            throw new AssertionError(method.getName());
        });
        var query = hana ? TestClients.offline(new HanaDatabaseConfiguration())
                : TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        var context = query.queryable(TestEvent.class).getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext();
        var executor = hana ? new HanaJdbcSQLExecutor() : new PgJdbcSQLExecutor();
        var actual = assertThrows(Throwable.class, () -> executor.query(
                ExecutorContext.create(context, true, ExecuteMethodEnum.LIST), easyConnection, "SELECT 1", List.of(), true, false));
        assertSame(original, actual);
        // 不重复关闭底层资源，也不让同一异常实例触发 Throwable 的 self-suppression 错误。
        assertEquals(mode == Cleanup.SAME ? List.of() : List.of(cleanup), List.of(actual.getSuppressed()));
        assertEquals(1, resultCloses.get());
        assertEquals(1, statementCloses.get());
    }

    private enum Cleanup {RUNTIME, ERROR, SAME}

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
