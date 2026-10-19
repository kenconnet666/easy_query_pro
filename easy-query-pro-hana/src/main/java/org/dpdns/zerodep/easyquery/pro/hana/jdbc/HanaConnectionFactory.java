package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.conn.EasyConnection;
import com.easy.query.core.basic.jdbc.conn.EasyConnectionFactory;
import com.easy.query.core.configuration.EasyQueryOption;
import com.easy.query.core.enums.conn.ConnectionStrategyEnum;
import com.easy.query.core.exception.EasyQuerySQLCommandException;
import com.easy.query.core.sharding.EasyQueryDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 保留上游路由与连接包装，只补充隔离级别校验和初始化失败时的资源归还。
 */
public final class HanaConnectionFactory implements EasyConnectionFactory {
    private final EasyQueryDataSource dataSources;
    private final long waitTimeoutMillis;

    public HanaConnectionFactory(EasyQueryDataSource dataSources, EasyQueryOption options) {
        this.dataSources = Objects.requireNonNull(dataSources, "dataSources");
        waitTimeoutMillis = Objects.requireNonNull(options, "options").getMultiConnWaitTimeoutMillis();
    }

    @Override
    public List<EasyConnection> createEasyConnections(int count, String dataSource, Integer isolation,
                                                      ConnectionStrategyEnum strategy) {
        if (count < 1) throw new IllegalArgumentException("Connection count must be positive");
        return acquire(count, dataSource, isolation, strategy, true);
    }

    @Override
    public EasyConnection createEasyConnection(String dataSource, Integer isolation, ConnectionStrategyEnum strategy) {
        return acquire(1, dataSource, isolation, strategy, false).get(0);
    }

    private List<EasyConnection> acquire(int count, String name, Integer isolation, ConnectionStrategyEnum strategy, boolean multiple) {
        if (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED
                && isolation != Connection.TRANSACTION_REPEATABLE_READ && isolation != Connection.TRANSACTION_SERIALIZABLE) {
            throw new IllegalArgumentException("HANA supports READ_COMMITTED, REPEATABLE_READ and SERIALIZABLE; requested " + isolation);
        }
        var source = dataSources.getDataSourceNotNull(name, strategy);
        var unit = source.getDataSourceUnit();
        List<Connection> borrowed = null;
        try {
            // 保留单/多连接的借用入口，避免绕过自定义 DataSourceUnit 的等待策略。
            borrowed = multiple ? unit.getConnections(count, waitTimeoutMillis, TimeUnit.MILLISECONDS)
                    : List.of(unit.getConnection(waitTimeoutMillis, TimeUnit.MILLISECONDS));
            var result = new ArrayList<EasyConnection>(borrowed.size());
            for (var connection : borrowed)
                result.add(new HanaEasyConnection(name, source.getStrategy(), connection, isolation));
            return result;
        } catch (SQLException failure) {
            release(borrowed, failure);
            throw new EasyQuerySQLCommandException(failure);
        } catch (RuntimeException | Error failure) {
            release(borrowed, failure);
            throw failure;
        }
    }

    private static void release(List<Connection> connections, Throwable failure) {
        if (connections == null) return;
        // 初始化未完成的连接还没有交给 ConnectionManager，必须释放整批，包括尚未包装的连接。
        for (var connection : connections) {
            try {
                connection.close();
            } catch (Throwable closeFailure) {
                // 与 try-with-resources 相同：保留最初的失败，同时继续清理其余连接。
                if (closeFailure != failure) failure.addSuppressed(closeFailure);
            }
        }
    }
}
