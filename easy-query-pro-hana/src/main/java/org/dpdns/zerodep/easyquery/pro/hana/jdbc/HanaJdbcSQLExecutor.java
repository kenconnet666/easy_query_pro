package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.extension.listener.JdbcExecuteAfterArg;
import com.easy.query.core.basic.extension.listener.JdbcExecuteBeforeArg;
import com.easy.query.core.basic.jdbc.conn.EasyConnection;
import com.easy.query.core.basic.jdbc.executor.ExecutorContext;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.impl.EasyStreamResultSet;
import com.easy.query.core.basic.jdbc.executor.impl.def.EntityResultColumnMetadata;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.exception.EasyQuerySQLStatementException;
import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.expression.lambda.SQLConsumer;
import com.easy.query.core.sql.DefaultJdbcSQLExecutor;
import com.easy.query.core.util.EasyJdbcExecutorUtil;
import org.dpdns.zerodep.easyquery.pro.hana.key.HanaSequenceGenerator;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 扩展回填插入生命周期和时间结果读取；查询执行、绑定、监听仍复用上游。
 */
public final class HanaJdbcSQLExecutor extends DefaultJdbcSQLExecutor {
    @Override
    public StreamResultSet query(ExecutorContext context, EasyConnection connection, String sql, List<SQLParameter> parameters,
                                 boolean sharding, boolean replica) throws SQLException {
        var query = new QueryContext(context);
        StreamResultSet result = null;
        try (var bindings = lobBindings(context)) {
            result = super.query(query, connection, sql, parameters, sharding, replica);
            var wrapped = HanaTemporalResultSet.wrap(result, query.statement.getResultSet());
            if (bindings != null) bindings.completed();
            return wrapped;
        } catch (SQLException | RuntimeException | Error failure) {
            if (result != null) try {
                result.close();
            } catch (SQLException | RuntimeException | Error cleanup) {
                // 清理只能补充诊断，不能覆盖包装失败；驱动也可能再次抛出同一异常实例。
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public <T> int executeRows(ExecutorContext context, EasyConnection connection, String sql, List<T> entities,
                               List<SQLParameter> parameters, boolean sharding, boolean replica) throws SQLException {
        try (var bindings = lobBindings(context)) {
            int rows = super.executeRows(context, connection, sql, entities, parameters, sharding, replica);
            if (bindings != null) bindings.completed();
            return rows;
        }
    }

    @Override
    public int executeRows(ExecutorContext context, EasyConnection connection, String sql, List<SQLParameter> parameters,
                           boolean sharding, boolean replica) throws SQLException {
        try (var bindings = lobBindings(context)) {
            int rows = super.executeRows(context, connection, sql, parameters, sharding, replica);
            if (bindings != null) bindings.completed();
            return rows;
        }
    }

    private static HanaLobBindings.Scope lobBindings(ExecutorContext context) {
        var handlers = context.getRuntimeContext().getJdbcTypeHandlerManager();
        return handlers instanceof HanaJdbcTypeHandlerManager hana ? hana.openLobBindings() : null;
    }

    private static final class QueryContext extends ExecutorContext {
        private final ExecutorContext original;
        private Statement statement;

        private QueryContext(ExecutorContext original) {
            super(original.getExpressionContext(), original.isQuery(), original.getExecuteMethod(), original.isTracking());
            this.original = original;
            setMapToBeanStrict(original.isMapToBeanStrict());
        }

        @Override
        public SQLConsumer<Statement> getConfigurer(boolean sharding) {
            var configure = original.getConfigurer(sharding);
            return prepared -> {
                // 上游分片结果不暴露原始 ResultSet；通过公开 Statement 配置钩子取得它，不读取私有字段。
                statement = prepared;
                if (configure != null) configure.accept(prepared);
            };
        }

        @Override
        public LocalDateTime getCreateTime() {
            return original.getCreateTime();
        }

        @Override
        public String getJdbcListenerTraceId() {
            return original.getJdbcListenerTraceId();
        }

        @Override
        public void setJdbcListenerTraceId(String value) {
            original.setJdbcListenerTraceId(value);
        }
    }

    @Override
    public <T> int insert(ExecutorContext context, EasyConnection connection, String sql, List<T> entities,
                          List<SQLParameter> parameters, boolean fillKeys, boolean sharding, boolean replica)
            throws SQLException {
        try (var bindings = lobBindings(context)) {
            int rows = insertRows(context, connection, sql, entities, parameters, fillKeys, sharding, replica);
            if (bindings != null) bindings.completed();
            return rows;
        }
    }

    /**
     * 只接管需要主键回填的插入，其他路径继续交给上游执行器。
     *
     * <p>identity/sequence 通过当前物理连接的会话值读取，每次 INSERT 后必须立即读取对应键，
     * 两者之间不能运行可能执行嵌套 SQL 的用户监听器。批次复用 statement，但逐行取键，
     * 不能替换成 JDBC batch 后读取最后一个会话值。
     *
     * <p>本方法不创建或提交事务。中途失败是否整批回滚由调用方事务决定；已经写回 Java 实体的键
     * 不会因数据库回滚自动撤销。带分片标记的会话取键当前明确拒绝，分片写入可使用应用生成的键。
     */
    private <T> int insertRows(ExecutorContext context, EasyConnection connection, String sql, List<T> entities,
                               List<SQLParameter> parameters, boolean fillKeys, boolean sharding, boolean replica) throws SQLException {
        if (context instanceof HanaInsertExecution.Context insertContext) {
            insertContext.checkConflictTarget(connection.getConnection());
        }
        if (!fillKeys || entities.isEmpty()) {
            return super.insert(context, connection, sql, entities, parameters, fillKeys, sharding, replica);
        }
        if (sharding) {
            throw new SQLException("HANA session-key backfill requires a resolved physical table; use application-generated keys for sharded writes", "0A000");
        }
        var runtime = context.getRuntimeContext();
        var metadata = runtime.getEntityMetadataManager().getEntityMetadata(entities.get(0).getClass());
        List<ColumnMetadata> columns = metadata.getGeneratedKeyColumns().stream()
                .map(name -> metadata.getColumnNotNull(metadata.getPropertyNameNotNull(name))).toList();
        String keyQuery = generatedKeyQuery(columns);
        var listener = runtime.getJdbcExecutorListener();
        boolean listen = listener.enable() && context.getExpressionContext().getBehavior().hasBehavior(EasyBehaviorEnum.JDBC_LISTEN);
        boolean print = EasyJdbcExecutorUtil.isPrintSQL(context);
        var handlers = runtime.getJdbcTypeHandlerManager();
        EasyJdbcExecutorUtil.logSQL(print, sql, connection, sharding, replica);
        JdbcExecuteBeforeArg before = null;
        PreparedStatement insert = null;
        PreparedStatement keys = null;
        Exception failure = null;
        int total = 0;
        try {
            if (listen) {
                String trace = listener.createTraceId();
                context.setJdbcListenerTraceId(trace);
                before = new JdbcExecuteBeforeArg(trace, sql, new ArrayList<>(entities.size()), context.getExecuteMethod());
                listener.onExecuteBefore(before);
            }
            if (!(context instanceof HanaInsertExecution.Context insertContext)) {
                throw new SQLException("HANA generated-key backfill requires HanaInsertExecution registration", "0A000");
            }
            insertContext.checkGeneratedKeySafety(connection.getConnection());
            // INSERT 与键读取之间不能调用用户监听器，否则嵌套 SQL 会覆盖当前连接的 session 值。
            // 同批复用两个 statement，但仍逐行读取键，避免 batch 只留下最后一个 identity。
            keys = connection.getConnection().prepareStatement(keyQuery);
            for (T entity : entities) {
                var values = EasyJdbcExecutorUtil.extractParameters(entity, parameters, print,
                        runtime.getSQLParameterPrintFormat(), connection, sharding, replica);
                if (before != null) before.getSqlParameters().add(values);
                if (insert == null) {
                    insert = EasyJdbcExecutorUtil.createPreparedStatement(connection.getConnection(), sql, values, handlers);
                } else {
                    EasyJdbcExecutorUtil.setPreparedStatement(insert, values, handlers);
                }
                int affected = insert.executeUpdate();
                total = Math.addExact(total, affected);
                if (affected != 1) {
                    throw new SQLException("HANA generated-key insert expected one affected row, received " + affected);
                }
                EasyJdbcExecutorUtil.logSQL(print, keyQuery, connection, sharding, replica);
                fillKeys(keys, columns, entity);
            }
            EasyJdbcExecutorUtil.logResult(print, total, connection, sharding, replica);
            return total;
        } catch (SQLException exception) {
            failure = exception;
            throw new EasyQuerySQLStatementException(sql, exception);
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            EasyJdbcExecutorUtil.clear(keys);
            EasyJdbcExecutorUtil.clear(insert);
            if (before != null) {
                try {
                    listener.onExecuteAfter(new JdbcExecuteAfterArg(before, null, total, failure));
                } catch (RuntimeException callbackFailure) {
                    if (failure == null) throw callbackFailure;
                    failure.addSuppressed(callbackFailure);
                }
            }
        }
    }

    private <T> void fillKeys(PreparedStatement statement, List<ColumnMetadata> columns, T entity) throws SQLException {
        // ResultSet 每行关闭；statement 留给下一行复用，由外层 finally 统一释放。
        try (var result = new EasyStreamResultSet(statement.executeQuery(), null)) {
            if (!result.next()) throw new SQLException("HANA did not return generated keys");
            for (int i = 0; i < columns.size(); i++) {
                var column = columns.get(i);
                var mapping = new EntityResultColumnMetadata(i, column.getEntityMetadata(), column);
                Object raw = mapping.getJdbcTypeHandler().getValue(mapping.getJdbcProperty(), result);
                // 生成键也走普通读取的解密/ValueConverter 路径，不能把 JDBC 值直接塞入业务值对象。
                Object key = EasyJdbcExecutorUtil.fromValue(mapping, raw);
                if (key == null) throw new SQLException("HANA generated key is null for " + column.getPropertyName());
                mapping.setValue(entity, key);
            }
        }
    }

    /**
     * 按生成列顺序构造同一会话中的取键查询。
     * 每个 identity/sequence 来源只允许消费一次：若同一来源被多个列消耗，CURRVAL 无法还原每列的键。
     * 仅识别本包注册的 HanaSequenceGenerator，不从任意自定义生成器 SQL 猜测会话取键方式。
     */
    private String generatedKeyQuery(List<ColumnMetadata> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("HANA key backfill requires @Column(generatedKey = true)");
        }
        var expressions = new ArrayList<String>(columns.size());
        var sources = new HashSet<String>();
        for (var column : columns) {
            var generator = column.getGeneratedSQLColumnGenerator();
            String expression;
            if (generator == null) {
                expression = "CURRENT_IDENTITY_VALUE()";
            } else if (generator instanceof HanaSequenceGenerator sequence) {
                expression = sequence.currentValueExpression();
            } else {
                throw new UnsupportedOperationException("HANA cannot backfill custom generator "
                        + generator.getClass().getName() + " for " + column.getPropertyName()
                        + "; use HanaSequenceGenerator or supply the key before inserting");
            }
            if (!sources.add(expression)) {
                throw new IllegalArgumentException("Multiple generated columns cannot share a HANA session key source: " + expression);
            }
            expressions.add(expression);
        }
        return "SELECT " + String.join(",", expressions) + " FROM DUMMY";
    }
}
