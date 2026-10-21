package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.sap.db.jdbc.ConnectionSapDB;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JDBC 没有 DDL 自动提交属性，通过 SAP 的公开 unwrap API 保存和恢复。
 */
final class HanaDdlSession implements AutoCloseable {
    private final Connection connection;
    private final boolean previousDdlAutoCommit;

    private HanaDdlSession(Connection connection, boolean previousDdlAutoCommit) {
        this.connection = connection;
        this.previousDdlAutoCommit = previousDdlAutoCommit;
    }

    static HanaDdlSession begin(Connection connection) throws SQLException {
        if (connection.getAutoCommit())
            throw new SQLException("HANA DDL scope requires a newly opened transaction", "25000");
        HanaDdlSession session = new HanaDdlSession(connection, connection.unwrap(ConnectionSapDB.class).isDDLAutoCommit());
        try {
            if (session.previousDdlAutoCommit) setMode(connection, false);
            return session;
        } catch (SQLException failure) {
            try {
                session.close();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    static void requireAmbientTransaction(Connection connection) throws SQLException {
        if (connection.getAutoCommit() || connection.unwrap(ConnectionSapDB.class).isDDLAutoCommit()) {
            // 切换 DDL 模式会隐式提交；不能借此提交调用方尚未完成的 DML。
            throw new SQLException("Ambient HANA migrations require autoCommit=false and AUTOCOMMIT DDL OFF before transaction work begins", "25000");
        }
    }

    private static void setMode(Connection connection, boolean enabled) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET TRANSACTION AUTOCOMMIT DDL " + (enabled ? "ON" : "OFF"));
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            // 先结束事务再恢复模式，避免 ON 的隐式提交把失败的迁移落盘。
            connection.rollback();
            if (connection.unwrap(ConnectionSapDB.class).isDDLAutoCommit() != previousDdlAutoCommit) {
                setMode(connection, previousDdlAutoCommit);
            }
        } catch (SQLException failure) {
            // 状态无法恢复的物理连接不能回到池中污染后续业务。
            try {
                connection.abort(Runnable::run);
            } catch (SQLException abortFailure) {
                failure.addSuppressed(abortFailure);
            }
            throw failure;
        }
    }
}
