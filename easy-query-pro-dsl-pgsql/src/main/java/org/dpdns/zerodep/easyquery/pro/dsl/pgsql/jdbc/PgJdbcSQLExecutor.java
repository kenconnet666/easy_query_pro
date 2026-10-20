package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.jdbc;

import com.easy.query.core.basic.jdbc.conn.EasyConnection;
import com.easy.query.core.basic.jdbc.executor.ExecutorContext;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.sql.DefaultJdbcSQLExecutor;

import java.sql.SQLException;
import java.util.List;

/**
 * 在分片内存合并前保留 PostgreSQL 无时区 timestamp；SQL 执行、绑定、监听及连接归还继续使用上游。
 */
public class PgJdbcSQLExecutor extends DefaultJdbcSQLExecutor {
    @Override
    public StreamResultSet query(ExecutorContext context, EasyConnection connection, String sql,
                                 List<SQLParameter> parameters, boolean sharding, boolean replica) throws SQLException {
        // 普通查询的 typed handler 已能直接保留字段，不改变自定义 handler 看到的无类型 JDBC 值。
        if (!sharding) return super.query(context, connection, sql, parameters, false, replica);
        StreamResultSet result = null;
        try {
            result = super.query(context, connection, sql, parameters, true, replica);
            return PgTemporalResultSet.wrap(result);
        } catch (SQLException | RuntimeException | Error failure) {
            if (result != null) try {
                result.close();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

}
