package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.column.ColumnFunctionExpression;
import com.easy.query.core.func.column.impl.ColumnFunctionExpressionImpl;
import com.easy.query.core.func.def.AnySQLFunction;

import java.util.List;
import java.util.Objects;

final class HanaCastFunctions {
    private HanaCastFunctions() {
    }

    static SQLFunction cast(List<ColumnExpression> arguments, Class<?> target) {
        if (arguments.size() != 1) throw new IllegalArgumentException("cast requires one argument");
        Objects.requireNonNull(target, "target");
        // 上游数字/布尔 getter 从 JSON fragment 继续 CAST，必须先取 scalar，避免把 JSON null 当成字符串 "null"。
        if (target != String.class && arguments.get(0) instanceof ColumnFunctionExpression function
                && function.getSQLFunction() instanceof HanaJsonFunction json) {
            arguments = List.of(new ColumnFunctionExpressionImpl(function.getTableOrNull(), json.scalar()));
        }
        String template = switch (target.getName()) {
            case "boolean", "java.lang.Boolean" -> "CAST({0} AS BOOLEAN)";
            // HANA TINYINT 是无符号的，不能用于 Java Byte 的负值范围。
            case "byte", "java.lang.Byte", "short", "java.lang.Short" -> "CAST({0} AS SMALLINT)";
            case "int", "java.lang.Integer" -> "CAST({0} AS INTEGER)";
            case "long", "java.lang.Long" -> "CAST({0} AS BIGINT)";
            case "float", "java.lang.Float" -> "CAST({0} AS REAL)";
            case "double", "java.lang.Double" -> "CAST({0} AS DOUBLE)";
            case "java.math.BigDecimal" -> "CAST({0} AS DECIMAL(36,18))";
            case "java.sql.Date", "java.time.LocalDate" -> "CAST({0} AS DATE)";
            case "java.sql.Time", "java.time.LocalTime" -> "CAST({0} AS TIME)";
            case "java.sql.Timestamp", "java.util.Date", "java.time.LocalDateTime" -> "CAST({0} AS TIMESTAMP)";
            case "char", "java.lang.Character" -> "SUBSTRING(TO_NVARCHAR({0}),1,1)";
            // 显式 NVARCHAR(5000) 会静默截短 NCLOB；普通 toStr 不应引入人为长度上限。
            case "java.lang.String" -> "TO_NVARCHAR({0})";
            case "java.util.UUID" -> "LOWER(TO_NVARCHAR({0}))";
            default -> throw new IllegalArgumentException("Unsupported HANA cast target: " + target.getName());
        };
        return new AnySQLFunction(template, List.copyOf(arguments));
    }
}
