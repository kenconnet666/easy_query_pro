package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.executor.DefaultEntityExpressionPrepareExecutor;
import com.easy.query.core.basic.jdbc.executor.EntityExpressionExecutor;
import com.easy.query.core.basic.jdbc.executor.ExecutorContext;
import com.easy.query.core.expression.sql.builder.EntityInsertExpressionBuilder;
import com.easy.query.core.trigger.EntityExpressionTrigger;
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaTableSQLExpression;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/** 在上游准备阶段传递结构化目标，执行阶段仍使用上游分发与连接管理。 */
public final class HanaInsertExecution extends DefaultEntityExpressionPrepareExecutor {
    public HanaInsertExecution(EntityExpressionExecutor executor, EntityExpressionTrigger trigger) {
        super(executor, trigger);
    }

    @Override
    public <T> long insert(ExecutorContext context, List<T> entities, EntityInsertExpressionBuilder builder, boolean fillKeys) {
        if (!fillKeys || entities.isEmpty()) return super.insert(context, entities, builder, fillKeys);
        if (context.getExpressionContext().isSharding()) {
            throw new UnsupportedOperationException("HANA sharded inserts require application-generated keys; session-key targets are not resolved");
        }
        var copy = builder.cloneEntityExpressionBuilder();
        var table = copy.getTable(0);
        if (!(table.toExpression() instanceof HanaTableSQLExpression expression)) {
            throw new UnsupportedOperationException("HANA generated-key backfill requires a structured HANA table expression");
        }
        var target = expression.insertTarget();
        var keyword = new HanaSQLKeyword();
        // 固定本次执行的 asTable/asSchema 结果，确保元数据检查和 INSERT 指向同一个对象。
        table.setTableNameAs(ignored -> keyword.quoteUnquotedName(target.table()));
        table.setSchemaAs(ignored -> target.schema() == null ? null : keyword.quoteUnquotedName(target.schema()));
        return super.insert(new Context(context, target), entities, copy, true);
    }

    static final class Context extends ExecutorContext {
        private final ExecutorContext original;
        private final HanaTableSQLExpression.InsertTarget target;

        Context(ExecutorContext original, HanaTableSQLExpression.InsertTarget target) {
            super(original.getExpressionContext(), original.isQuery(), original.getExecuteMethod(), original.isTracking());
            this.original = original;
            this.target = target;
            setMapToBeanStrict(original.isMapToBeanStrict());
            setConfigurer(original.getConfigurer(false));
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

        void checkGeneratedKeySafety(Connection connection) throws SQLException {
            // 不缓存触发器状态；schema 变更后不能继续沿用过期的“安全”结论。
            String sql = "SELECT r.TRIGGER_NAME FROM SYS.TABLES t LEFT JOIN SYS.TRIGGERS r"
                    + " ON r.SUBJECT_TABLE_SCHEMA = t.SCHEMA_NAME AND r.SUBJECT_TABLE_NAME = t.TABLE_NAME"
                    + " AND r.TRIGGER_EVENT LIKE '%INSERT%' AND r.IS_ENABLED = 'TRUE'"
                    + " WHERE t.SCHEMA_NAME = COALESCE(?,CURRENT_SCHEMA) AND t.TABLE_NAME = ? LIMIT 1";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, target.schema());
                statement.setString(2, target.table());
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("HANA generated-key backfill requires visible base-table metadata for "
                                + target.table() + "; views and synonyms require application-generated keys or executeRows(false)", "0A000");
                    }
                    if (result.getString(1) != null) {
                        throw new SQLException("HANA session-key backfill is unsafe with enabled INSERT trigger "
                                + result.getString(1) + " on " + target.table()
                                + "; use application-generated keys or executeRows(false)", "0A000");
                    }
                }
            }
        }
    }
}
