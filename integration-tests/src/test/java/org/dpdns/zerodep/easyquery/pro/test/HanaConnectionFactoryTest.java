package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.enums.conn.ConnectionStrategyEnum;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaConnectionFactory;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HanaConnectionFactoryTest {
    @Test
    void failedInitializationReleasesEveryBorrowedConnectionAndKeepsCloseFailures() {
        var closes = new ArrayList<AtomicInteger>();
        var initializationFailure = new SQLException("fixture isolation failure");
        var closeFailure = new SQLException("fixture close failure");
        DataSource source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("getConnection")) throw new AssertionError(method.getName());
                    int index = closes.size();
                    var closeCount = new AtomicInteger();
                    closes.add(closeCount);
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (connection, operation, values) -> {
                        switch (operation.getName()) {
                            case "setTransactionIsolation":
                                if (index == 1) throw initializationFailure;
                                return null;
                            case "close":
                                closeCount.incrementAndGet();
                                if (index == 2) throw closeFailure;
                                return null;
                            default:
                                throw new AssertionError(operation.getName());
                        }
                    });
                });
        var runtime = TestClients.offline(new HanaDatabaseConfiguration()).getRuntimeContext();
        runtime.getEasyQueryDataSource().addDataSource("fixture", source, 3);
        var factory = new HanaConnectionFactory(runtime.getEasyQueryDataSource(), runtime.getQueryConfiguration().getEasyQueryOption());
        var failure = assertThrows(RuntimeException.class, () -> factory.createEasyConnections(3, "fixture",
                Connection.TRANSACTION_READ_COMMITTED, ConnectionStrategyEnum.IndependentConnectionMaster));
        assertSame(initializationFailure, failure.getCause());
        assertEquals(List.of(closeFailure), List.of(failure.getSuppressed()));
        assertEquals(List.of(1, 1, 1), closes.stream().map(AtomicInteger::get).toList());
    }

    @Test
    void unsupportedIsolationIsRejectedBeforeAccessingTheDataSource() {
        var runtime = TestClients.offline(new HanaDatabaseConfiguration()).getRuntimeContext();
        var factory = new HanaConnectionFactory(runtime.getEasyQueryDataSource(), runtime.getQueryConfiguration().getEasyQueryOption());
        assertThrows(IllegalArgumentException.class, () -> factory.createEasyConnection(
                runtime.getEasyQueryDataSource().getDefaultDataSourceName(), Connection.TRANSACTION_READ_UNCOMMITTED,
                ConnectionStrategyEnum.ShareConnection));
    }
}
