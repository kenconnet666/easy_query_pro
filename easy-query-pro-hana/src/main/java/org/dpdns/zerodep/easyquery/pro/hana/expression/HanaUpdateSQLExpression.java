package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.sql.expression.EntityTableSQLExpression;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.core.expression.sql.expression.impl.UpdateSQLExpressionImpl;
import com.easy.query.core.expression.segment.InsertUpdateSetColumnSQLSegment;
import com.easy.query.core.util.EasySQLExpressionUtil;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

import java.util.StringJoiner;
import com.easy.query.core.metadata.EntityMetadata;
import java.util.Collection;
import com.easy.query.core.expression.segment.SQLSegment;

final class HanaUpdateSQLExpression extends UpdateSQLExpressionImpl {
    HanaUpdateSQLExpression(EntitySQLExpressionMetadata metadata, EntityTableSQLExpression table) {
        super(metadata, table);
    }

    @Override
    public String toSQL(ToSQLContext context) {
        if (tables.size() == 1 || setColumns.isEmpty()) return super.toSQL(context);
        EasySQLExpressionUtil.expressionInvokeRoot(context);
        if (where.isEmpty()) throw new IllegalArgumentException("HANA joined UPDATE requires a WHERE predicate");
        if (!(tables.get(0) instanceof HanaTableSQLExpression)) {
            throw new UnsupportedOperationException("HANA joined UPDATE requires a base-table target");
        }
        HanaTableSQLExpression root = (HanaTableSQLExpression) tables.get(0);
        EntityMetadata metadata = root.getEntityMetadata();
        Collection<String> keys = metadata.getKeyProperties();
        if (keys.isEmpty())
            throw new UnsupportedOperationException("HANA joined UPDATE requires modeled primary keys for deterministic row matching");
        String target = root.writeTargetSql(context);
        String sourceAlias = HanaTableSQLExpression.unusedAlias("eqp_update", tables, context);
        String targetAlias = context.getAlias(root.getEntityTable());
        if (targetAlias == null) throw new IllegalStateException("HANA joined UPDATE target has no table alias");
        HanaSQLKeyword keyword = new HanaSQLKeyword();
        StringJoiner projection = new StringJoiner(",");
        StringJoiner match = new StringJoiner(" AND ");
        int keyIndex = 0;
        for (String property : keys) {
            String column = targetAlias + "." + keyword.getQuoteName(metadata.getColumnNotNull(property).getName());
            String alias = keyword.getQuoteName("eqp_key_" + keyIndex++);
            projection.add(column + " AS " + alias);
            match.add(column + " = " + sourceAlias + "." + alias);
        }
        StringJoiner assignments = new StringJoiner(",");
        int valueIndex = 0;
        for (SQLSegment segment : setColumns.getSQLSegments()) {
            if (!(segment instanceof InsertUpdateSetColumnSQLSegment) || ((InsertUpdateSetColumnSQLSegment) segment).getTable() != root.getEntityTable()) {
                throw new IllegalArgumentException("HANA joined UPDATE can only assign structured root-table columns");
            }
            String alias = keyword.getQuoteName("eqp_value_" + valueIndex++);
            projection.add(((InsertUpdateSetColumnSQLSegment) segment).toSQL(context) + " AS " + alias);
            assignments.add(((InsertUpdateSetColumnSQLSegment) segment).getColumnNameWithOwner(context) + " = " + sourceAlias + "." + alias);
        }
        // UPDATE FROM 允许一行被多次更新且顺序不确定；MERGE 对重复源行报错，保持原子失败。
        StringBuilder sql = new StringBuilder("MERGE INTO ").append(target).append(" USING (SELECT ").append(projection)
                .append(" FROM ").append(target);
        HanaTableSQLExpression.appendJoins(sql, tables, context);
        sql.append(" WHERE ").append(where.toSQL(context)).append(") ").append(sourceAlias)
                .append(" ON (").append(match).append(") WHEN MATCHED THEN UPDATE SET ").append(assignments);
        return sql.toString();
    }
}
