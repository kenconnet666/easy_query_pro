package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.enums.MultiTableTypeEnum;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.sql.expression.impl.TableSQLExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

/** 从结构化表信息取得执行目标，不反向解析 SQL 文本。 */
public final class HanaTableSQLExpression extends TableSQLExpressionImpl {
    public HanaTableSQLExpression(TableAvailable table, MultiTableTypeEnum type, QueryRuntimeContext runtime) {
        super(table, type, runtime);
    }

    public InsertTarget insertTarget() {
        if (segmentAs != null || linkAs != null) {
            throw new UnsupportedOperationException("HANA generated-key backfill requires a structured table target");
        }
        String name = entityTable.getTableName();
        if (tableNameAs != null) name = tableNameAs.apply(name);
        String schema = runtimeContext.getRuntimeSchemaProvider().getSchema(entityTable.getEntityMetadata());
        if (schemaAs != null) schema = schemaAs.apply(schema);
        if (schema != null && schema.isBlank()) schema = null;
        if ((name != null && name.contains(".")) || (schema != null && schema.contains("."))) {
            throw new UnsupportedOperationException("HANA generated-key targets require separate schema and table identifiers; use asSchema");
        }
        var keyword = new HanaSQLKeyword();
        return new InsertTarget(schema == null ? null : keyword.unquoteName(schema), keyword.unquoteName(name));
    }

    public record InsertTarget(String schema, String table) {
    }
}
