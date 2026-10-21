package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.column.ColumnFunctionExpression;
import com.easy.query.core.func.column.impl.ColumnFunctionExpressionImpl;
import com.easy.query.core.func.def.AnySQLFunction;

import java.util.List;
import java.util.Objects;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;

final class HanaCastFunctions {
    private HanaCastFunctions() {
    }

    static SQLFunction cast(List<ColumnExpression> arguments, Class<?> target) {
        if (arguments.size() != 1) throw new IllegalArgumentException("cast requires one argument");
        Objects.requireNonNull(target, "target");
        // 上游数字/布尔 getter 从 JSON fragment 继续 CAST，必须先取 scalar，避免把 JSON null 当成字符串 "null"。
        if (target != String.class && arguments.get(0) instanceof ColumnFunctionExpression) {
            ColumnFunctionExpression function = (ColumnFunctionExpression) arguments.get(0);
            if (function.getSQLFunction() instanceof HanaJsonFunction) {
                HanaJsonFunction json = (HanaJsonFunction) function.getSQLFunction();
                arguments = ImmutableCollections.listOf(new ColumnFunctionExpressionImpl(function.getTableOrNull(), json.scalar()));
            }
        }
        String template;
        switch (target.getName()) {
            case "boolean": case "java.lang.Boolean": template = "CAST({0} AS BOOLEAN)"; break;
            case "byte": case "java.lang.Byte": case "short": case "java.lang.Short": template = "CAST({0} AS SMALLINT)"; break;
            case "int": case "java.lang.Integer": template = "CAST({0} AS INTEGER)"; break;
            case "long": case "java.lang.Long": template = "CAST({0} AS BIGINT)"; break;
            case "float": case "java.lang.Float": template = "CAST({0} AS REAL)"; break;
            case "double": case "java.lang.Double": template = "CAST({0} AS DOUBLE)"; break;
            case "java.math.BigDecimal": template = "CAST({0} AS DECIMAL(36,18))"; break;
            case "java.sql.Date": case "java.time.LocalDate": template = "CAST({0} AS DATE)"; break;
            case "java.sql.Time": case "java.time.LocalTime": template = "CAST({0} AS TIME)"; break;
            case "java.sql.Timestamp": case "java.util.Date": case "java.time.LocalDateTime": template = "CAST({0} AS TIMESTAMP)"; break;
            case "char": case "java.lang.Character": template = "SUBSTRING(TO_NVARCHAR({0}),1,1)"; break;
            case "java.lang.String": template = "TO_NVARCHAR({0})"; break;
            case "java.util.UUID": template = "LOWER(TO_NVARCHAR({0}))"; break;
            default: throw new IllegalArgumentException("Unsupported HANA cast target: " + target.getName());
        }
        return new AnySQLFunction(template, ImmutableCollections.listCopyOf(arguments));
    }
}
