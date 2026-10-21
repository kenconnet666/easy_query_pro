package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.executor.DefaultEntityExpressionPrepareExecutor;
import com.easy.query.core.basic.jdbc.executor.EntityExpressionExecutor;
import com.easy.query.core.basic.jdbc.executor.ExecutorContext;
import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.expression.sql.builder.EntityInsertExpressionBuilder;
import com.easy.query.core.trigger.EntityExpressionTrigger;
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaTableSQLExpression;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import com.easy.query.core.expression.sql.builder.internal.EasyBehavior;
import com.easy.query.core.expression.sql.builder.EntityTableExpressionBuilder;
import com.easy.query.core.metadata.EntityMetadata;
import java.util.Collection;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 在上游准备阶段传递结构化目标，执行阶段仍使用上游分发与连接管理。
 */
public final class HanaInsertExecution extends DefaultEntityExpressionPrepareExecutor {
    public HanaInsertExecution(EntityExpressionExecutor executor, EntityExpressionTrigger trigger) {
        super(executor, trigger);
    }

    @Override
    public <T> long insert(ExecutorContext context, List<T> entities, EntityInsertExpressionBuilder builder, boolean fillKeys) {
        EasyBehavior behavior = context.getExpressionContext().getBehavior();
        boolean conflict = behavior.hasBehavior(EasyBehaviorEnum.ON_DUPLICATE_KEY_IGNORE)
                || behavior.hasBehavior(EasyBehaviorEnum.ON_DUPLICATE_KEY_UPDATE);
        if ((!fillKeys && !conflict) || entities.isEmpty()) return super.insert(context, entities, builder, fillKeys);
        if (fillKeys && conflict) {
            throw new UnsupportedOperationException("HANA MERGE cannot backfill session-generated keys; use executeRows(false) or application-generated keys");
        }
        if (context.getExpressionContext().isSharding()) {
            throw new UnsupportedOperationException("HANA guarded inserts require a resolved physical table; sharded targets are not resolved");
        }
        EntityInsertExpressionBuilder copy = builder.cloneEntityExpressionBuilder();
        EntityTableExpressionBuilder table = copy.getTable(0);
        if (!(table.toExpression() instanceof HanaTableSQLExpression)) {
            throw new UnsupportedOperationException("HANA guarded writes require a structured HANA table expression");
        }
        HanaTableSQLExpression expression = (HanaTableSQLExpression) table.toExpression();
        HanaTableSQLExpression.InsertTarget target = expression.insertTarget();
        HanaSQLKeyword keyword = new HanaSQLKeyword();
        // 固定本次执行的 asTable/asSchema 结果，确保元数据检查和实际写入指向同一个对象。
        table.setTableNameAs(ignored -> keyword.quoteUnquotedName(target.table()));
        table.setSchemaAs(ignored -> target.schema() == null ? null : keyword.quoteUnquotedName(target.schema()));
        HashSet<String> keys = new HashSet<String>();
        if (conflict) {
            EntityMetadata metadata = table.getEntityMetadata();
            Collection<String> properties = builder.getDuplicateKeys() == null || builder.getDuplicateKeys().isEmpty()
                    ? metadata.getKeyProperties() : builder.getDuplicateKeys();
            for (String property : properties) keys.add(metadata.getColumnNotNull(property).getName());
        }
        return super.insert(new Context(context, target, keys), entities, copy, fillKeys);
    }

    static final class Context extends ExecutorContext {
        private final ExecutorContext original;
        private final HanaTableSQLExpression.InsertTarget target;
        private final Set<String> conflictKeys;

        Context(ExecutorContext original, HanaTableSQLExpression.InsertTarget target, Set<String> conflictKeys) {
            super(original.getExpressionContext(), original.isQuery(), original.getExecuteMethod(), original.isTracking());
            this.original = original;
            this.target = target;
            this.conflictKeys = ImmutableCollections.setCopyOf(conflictKeys);
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
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, target.schema());
                statement.setString(2, target.table());
                try (ResultSet result = statement.executeQuery()) {
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

        void checkConflictTarget(Connection connection) throws SQLException {
            if (conflictKeys.isEmpty()) return;
            // 元数据只用于校验唯一性；是否存在匹配行由一条原子 MERGE 判断，不先查数据再写入。
            String sql = "SELECT INDEX_NAME,COLUMN_NAME FROM SYS.INDEX_COLUMNS"
                    + " WHERE SCHEMA_NAME = COALESCE(?,CURRENT_SCHEMA) AND TABLE_NAME = ?"
                    + " AND \"CONSTRAINT\" IN ('PRIMARY KEY','UNIQUE')";
            LinkedHashMap<String, Set<String>> uniqueKeys = new LinkedHashMap<String, Set<String>>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, target.schema());
                statement.setString(2, target.table());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next())
                        uniqueKeys.computeIfAbsent(rows.getString(1), ignored -> new HashSet<>()).add(rows.getString(2));
                }
            }
            if (uniqueKeys.values().stream().noneMatch(conflictKeys::equals)) {
                throw new SQLException("HANA MERGE conflict columns must match a visible primary key or unique index on "
                        + target.table() + ": " + conflictKeys.stream().sorted().collect(ImmutableCollections.toList()), "0A000");
            }
        }
    }
}
