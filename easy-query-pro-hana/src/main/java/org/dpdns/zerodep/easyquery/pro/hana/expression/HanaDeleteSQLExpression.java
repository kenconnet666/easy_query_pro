package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.sql.expression.EntityTableSQLExpression;
import com.easy.query.core.expression.sql.expression.impl.DeleteSQLExpressionImpl;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.core.util.EasySQLExpressionUtil;

final class HanaDeleteSQLExpression extends DeleteSQLExpressionImpl {
    HanaDeleteSQLExpression(EntitySQLExpressionMetadata metadata, EntityTableSQLExpression table) {
        super(metadata, table);
    }

    @Override
    public String toSQL(ToSQLContext context) {
        if (tables.size() == 1) return super.toSQL(context);
        EasySQLExpressionUtil.expressionInvokeRoot(context);
        if (where.isEmpty()) throw new IllegalArgumentException("HANA joined DELETE requires a WHERE predicate");
        if (!(tables.get(0) instanceof HanaTableSQLExpression)) {
            throw new UnsupportedOperationException("HANA joined DELETE requires a base-table target");
        }
        HanaTableSQLExpression root = (HanaTableSQLExpression) tables.get(0);
        String target = root.writeTargetSql(context);
        String seed = HanaTableSQLExpression.unusedAlias("eqp_delete", tables, context);
        // 一行 DUMMY 代表当前外层目标行；保留 LEFT JOIN 的未匹配行，又不要求目标有主键。
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(target).append(" WHERE EXISTS (SELECT 1 FROM SYS.DUMMY ").append(seed);
        HanaTableSQLExpression.appendJoins(sql, tables, context);
        // RIGHT/FULL JOIN 补出的空根行不能触发删除。
        sql.append(" WHERE ").append(seed).append(".DUMMY IS NOT NULL AND (").append(where.toSQL(context)).append("))");
        return sql.toString();
    }
}
