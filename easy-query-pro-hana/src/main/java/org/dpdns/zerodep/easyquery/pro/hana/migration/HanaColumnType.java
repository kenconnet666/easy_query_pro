package org.dpdns.zerodep.easyquery.pro.hana.migration;

import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata.Column;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DDL 列类型；CAST 的默认长度和转换语义由函数层独立处理。
 */
record HanaColumnType(String name, Integer length, Integer scale) {
    private static final Pattern TYPE = Pattern.compile("([A-Z]+)(?:\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?");
    private static final Set<String> SIZED = Set.of("VARCHAR", "NVARCHAR", "VARBINARY", "ALPHANUM", "SHORTTEXT");
    private static final Set<String> FIXED = Set.of("TINYINT", "SMALLINT", "INTEGER", "BIGINT", "REAL", "DOUBLE",
            "SMALLDECIMAL", "BOOLEAN", "DATE", "TIME", "SECONDDATE", "TIMESTAMP", "BLOB", "CLOB", "NCLOB", "TEXT");
    private static final List<String> INTEGERS = List.of("TINYINT", "SMALLINT", "INTEGER", "BIGINT");

    static HanaColumnType parse(String sql) {
        if (sql == null) throw new IllegalArgumentException("HANA column type is required");
        var matcher = TYPE.matcher(sql.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid HANA column type: " + sql);
        String name = matcher.group(1);
        if (name.equals("INT")) name = "INTEGER";
        Integer length = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
        Integer scale = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
        if (SIZED.contains(name)) {
            // HANA DDL 省略字符串长度时是 1，不能沿用 CAST 的 5000 默认值。
            if (length == null) length = 1;
            int max = name.equals("ALPHANUM") ? 127 : 5000;
            if (length < 1 || length > max || scale != null) throw invalid(sql);
        } else if (name.equals("DECIMAL")) {
            if (length != null) {
                if (scale == null) scale = 0;
                if (length < 1 || length > 38 || scale < 0 || scale > length) throw invalid(sql);
            }
        } else if (!FIXED.contains(name) || length != null || scale != null) {
            throw invalid(sql);
        }
        return new HanaColumnType(name, length, scale);
    }

    static HanaColumnType from(Column column) {
        String type = column.type();
        if (SIZED.contains(type)) return parse(type + "(" + column.length() + ")");
        if (type.equals("DECIMAL") && column.length() != null && column.scale() != null) {
            return parse(type + "(" + column.length() + "," + column.scale() + ")");
        }
        return parse(type);
    }

    String sql() {
        if (length == null) return name;
        return name + "(" + length + (scale == null ? "" : "," + scale) + ")";
    }

    boolean acceptsIdentity() {
        return INTEGERS.contains(name);
    }

    boolean widens(HanaColumnType previous) {
        if (equals(previous)) return true;
        if (name.equals(previous.name)) {
            if (SIZED.contains(name)) return length >= previous.length;
            if (name.equals("DECIMAL") && length != null && previous.length != null) {
                return scale >= previous.scale && length - scale >= previous.length - previous.scale;
            }
        }
        if (INTEGERS.contains(name) && INTEGERS.contains(previous.name)) {
            return INTEGERS.indexOf(name) > INTEGERS.indexOf(previous.name);
        }
        return name.equals("DOUBLE") && previous.name.equals("REAL");
    }

    private static IllegalArgumentException invalid(String sql) {
        return new IllegalArgumentException("Unsupported HANA DDL type or parameters: " + sql);
    }
}
