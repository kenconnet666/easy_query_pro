package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.enums.MultiTableTypeEnum;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.sql.expression.impl.TableSQLExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;
import com.easy.query.core.expression.sql.expression.EntityTableSQLExpression;

import java.util.List;
import java.util.HashSet;

/**
 * 从结构化表信息取得执行目标，不反向解析 SQL 文本。
 */
public final class HanaTableSQLExpression extends TableSQLExpressionImpl {
    public HanaTableSQLExpression(TableAvailable table, MultiTableTypeEnum type, QueryRuntimeContext runtime) {
        super(table, type, runtime);
    }

    public InsertTarget insertTarget() {
        if (segmentAs != null || linkAs != null) {
            throw new UnsupportedOperationException("HANA guarded writes require a structured table target");
        }
        String name = entityTable.getTableName();
        if (tableNameAs != null) name = tableNameAs.apply(name);
        String schema = runtimeContext.getRuntimeSchemaProvider().getSchema(entityTable.getEntityMetadata());
        if (schemaAs != null) schema = schemaAs.apply(schema);
        if (schema != null && schema.isBlank()) schema = null;
        if ((name != null && name.contains(".")) || (schema != null && schema.contains("."))) {
            throw new UnsupportedOperationException("HANA guarded writes require separate schema and table identifiers; use asSchema");
        }
        var keyword = new HanaSQLKeyword();
        return new InsertTarget(schema == null ? null : keyword.unquoteName(schema), keyword.unquoteName(name));
    }

    String writeTargetSql(ToSQLContext context) {
        if (segmentAs != null || linkAs != null) {
            throw new UnsupportedOperationException("HANA joined writes require a structured root table");
        }
        return toSQL(context);
    }

    static void appendJoins(StringBuilder sql, List<EntityTableSQLExpression> tables, ToSQLContext context) {
        for (int i = 1; i < tables.size(); i++) {
            var table = tables.get(i);
            sql.append(table.toSQL(context));
            if (table.getOn() != null && table.getOn().isNotEmpty())
                sql.append(" ON ").append(table.getOn().toSQL(context));
        }
    }

    static String unusedAlias(String base, List<EntityTableSQLExpression> tables, ToSQLContext context) {
        var existing = new HashSet<String>();
        for (var table : tables) existing.add(context.getAlias(table.getEntityTable()));
        var keyword = new HanaSQLKeyword();
        String alias = base;
        for (int suffix = 1; existing.contains(alias) || existing.contains(keyword.getQuoteName(alias)); suffix++)
            alias = base + suffix;
        return alias;
    }

    public record InsertTarget(String schema, String table) {
    }
}
