package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.conn.impl.DefaultEasyConnection;
import com.easy.query.core.enums.conn.ConnectionStrategyEnum;
import com.easy.query.core.exception.EasyQuerySQLCommandException;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 保留上游事务生命周期，在连接归还前恢复 Code First 临时使用的 DDL 模式。
 */
public final class HanaEasyConnection extends DefaultEasyConnection {
    private HanaDdlSession ddlSession;

    public HanaEasyConnection(String source, ConnectionStrategyEnum strategy, Connection connection, Integer isolation) {
        super(source, strategy, connection, isolation);
    }

    /**
     * 仅供新事务在执行任何用户 SQL 之前调用；切换模式会隐式提交已有事务。
     */
    public void beginDdlScope() throws SQLException {
        if (ddlSession != null) throw new IllegalStateException("HANA DDL scope is already active");
        ddlSession = HanaDdlSession.begin(getConnection());
    }

    public static void requireTransactionalDdl(Connection connection) throws SQLException {
        HanaDdlSession.requireAmbientTransaction(connection);
    }

    @Override
    public void commit() throws SQLException {
        super.commit();
        restoreDdlMode();
    }

    @Override
    public void rollback() throws SQLException {
        super.rollback();
        restoreDdlMode();
    }

    @Override
    public void close() {
        try {
            restoreDdlMode();
        } catch (SQLException failure) {
            throw new EasyQuerySQLCommandException("Unable to restore HANA DDL session; physical connection aborted", failure);
        } finally {
            super.close();
        }
    }

    private void restoreDdlMode() throws SQLException {
        var session = ddlSession;
        ddlSession = null;
        if (session != null) session.close();
    }
}
