package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.expression.segment.InsertUpdateSetColumnSQLSegment;
import com.easy.query.core.expression.segment.SQLEntitySegment;
import com.easy.query.core.expression.sql.expression.EntityTableSQLExpression;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.core.expression.sql.expression.impl.InsertSQLExpressionImpl;
import com.easy.query.core.util.EasySQLExpressionUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.StringJoiner;

final class HanaInsertSQLExpression extends InsertSQLExpressionImpl {
    HanaInsertSQLExpression(EntitySQLExpressionMetadata metadata, EntityTableSQLExpression table) {
        super(metadata, table);
    }

    @Override
    public String toSQL(ToSQLContext context) {
        if (columns.isEmpty()) {
            throw new UnsupportedOperationException("HANA inserts require at least one writable column");
        }
        var behavior = entitySQLExpressionMetadata.getExpressionContext().getBehavior();
        if (behavior.hasBehavior(EasyBehaviorEnum.ON_DUPLICATE_KEY_IGNORE)
                || behavior.hasBehavior(EasyBehaviorEnum.ON_DUPLICATE_KEY_UPDATE)) {
            return merge(context, behavior.hasBehavior(EasyBehaviorEnum.ON_DUPLICATE_KEY_UPDATE));
        }
        return super.toSQL(context);
    }

    private String merge(ToSQLContext context, boolean update) {
        EasySQLExpressionUtil.expressionInvokeRoot(context);
        var table = tables.get(0);
        var metadata = table.getEntityMetadata();
        var inserted = new LinkedHashMap<String, InsertUpdateSetColumnSQLSegment>();
        for (var segment : columns.getSQLSegments()) {
            if (!(segment instanceof InsertUpdateSetColumnSQLSegment column)) {
                throw new IllegalArgumentException("HANA MERGE requires structured insert columns");
            }
            if (inserted.putIfAbsent(column.getPropertyName(), column) != null) {
                throw new IllegalArgumentException("Repeated MERGE source property: " + column.getPropertyName());
            }
        }
        var keys = duplicateKeys == null || duplicateKeys.isEmpty() ? List.copyOf(metadata.getKeyProperties()) : List.copyOf(duplicateKeys);
        if (keys.isEmpty() || !inserted.keySet().containsAll(keys)) {
            throw new IllegalArgumentException("HANA MERGE requires explicit primary or unique key values among the inserted columns");
        }
        String target = table.toSQL(context);
        var source = new StringJoiner(",");
        var names = new LinkedHashMap<String, String>();
        for (var entry : inserted.entrySet()) {
            String name = getColumnNameWithOwner(entry.getValue(), context);
            names.put(entry.getKey(), name);
            // 值仅在 source 中渲染一次，UPDATE/INSERT 复用别名，保证参数顺序和生成表达式一致。
            source.add(entry.getValue().toSQL(context) + " AS " + name);
        }
        var condition = new StringJoiner(" AND ");
        for (String key : keys) condition.add("eqp_target." + names.get(key) + " = eqp_source." + names.get(key));
        var sql = new StringBuilder("MERGE INTO ").append(target).append(" AS eqp_target USING (SELECT ")
                .append(source).append(" FROM DUMMY) AS eqp_source ON (").append(condition).append(')');
        if (update) {
            var assignments = new StringJoiner(",");
            for (var segment : getRealDuplicateKeyUpdateColumns().getSQLSegments()) {
                if (!(segment instanceof SQLEntitySegment column)) {
                    throw new IllegalArgumentException("HANA MERGE update selection requires entity properties");
                }
                String property = column.getPropertyName();
                if (keys.contains(property) || metadata.getKeyProperties().contains(property) || !names.containsKey(property))
                    continue;
                String name = names.get(property);
                assignments.add("eqp_target." + name + " = eqp_source." + name);
            }
            if (assignments.length() != 0) sql.append(" WHEN MATCHED THEN UPDATE SET ").append(assignments);
        }
        sql.append(" WHEN NOT MATCHED THEN INSERT (").append(String.join(",", names.values())).append(") VALUES (");
        var values = new StringJoiner(",");
        for (String name : names.values()) values.add("eqp_source." + name);
        return sql.append(values).append(')').toString();
    }
}
