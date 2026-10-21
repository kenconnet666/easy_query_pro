package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.column.ColumnFuncFormatExpression;
import com.easy.query.core.func.column.ColumnFuncValueExpression;
import com.easy.query.core.func.column.impl.ColumnFuncFormatExpressionImpl;
import com.easy.query.core.func.def.AbstractExpressionSQLFunction;
import com.easy.query.core.func.def.AnySQLFunction;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;

import java.util.List;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;

final class HanaJsonFunction extends AbstractExpressionSQLFunction {
    private final List<ColumnExpression> arguments;
    private final HanaOptions options;
    private final boolean fragment;

    private HanaJsonFunction(ColumnExpression document, String path, HanaOptions options, boolean fragment) {
        // 通过 format 参数插入已转义的 SQL 字面量，避免键中的 {0} 被当成模板占位符。
        arguments = ImmutableCollections.listOf(document, new ColumnFuncFormatExpressionImpl("'" + path.replace("'", "''") + "'"));
        this.options = options;
        this.fragment = fragment;
    }

    static HanaJsonFunction object(List<ColumnExpression> arguments, HanaOptions options, boolean fragment) {
        if (arguments.size() < 2) throw new IllegalArgumentException("JSON object extraction requires a document and a key");
        StringBuilder path = new StringBuilder("$");
        for (int i = 1; i < arguments.size(); i++) {
            Object key = constant(arguments.get(i));
            if (!(key instanceof String)) throw new IllegalArgumentException("JSON member names must be constant strings");
            String name = (String) key;
            path.append("[\"").append(escapeMember(name)).append("\"]");
        }
        return new HanaJsonFunction(arguments.get(0), path.toString(), options, fragment);
    }

    static HanaJsonFunction array(List<ColumnExpression> arguments, HanaOptions options, boolean fragment) {
        if (arguments.size() != 2) throw new IllegalArgumentException("JSON array extraction requires two arguments");
        Object value = constant(arguments.get(1));
        if (!(value instanceof Integer) || ((Integer) value) < 0) {
            throw new IllegalArgumentException("JSON array index must be a nonnegative int");
        }
        return new HanaJsonFunction(arguments.get(0), "$[" + ((Integer) value) + "]", options, fragment);
    }

    HanaJsonFunction scalar() {
        return new HanaJsonFunction(arguments, options);
    }

    private HanaJsonFunction(List<ColumnExpression> arguments, HanaOptions options) {
        this.arguments = arguments;
        this.options = options;
        fragment = false;
    }

    SQLFunction containsKey() {
        return new AnySQLFunction("(" + wrappedQuery() + " IS NOT NULL)", arguments);
    }

    @Override
    public String sqlSegment(TableAvailable table) {
        if (!fragment) return "JSON_VALUE({0},{1} RETURNING NVARCHAR(" + options.maxJsonValueUtf16Units() + ") ERROR ON ERROR)";
        // 强制数组包装让 JSON null、字符串和数字也可抽取；只移除外层 []，不再次解析文档。
        return "REPLACE_REGEXPR('^\\[|\\]$' IN " + wrappedQuery() + " WITH '' OCCURRENCE ALL)";
    }

    private String wrappedQuery() {
        return "JSON_QUERY({0},{1} RETURNING NVARCHAR(" + (options.maxJsonFragmentBytes() + 2)
                + ") WITH UNCONDITIONAL ARRAY WRAPPER ERROR ON ERROR)";
    }

    @Override
    public int paramMarks() {
        return arguments.size();
    }

    @Override
    protected List<ColumnExpression> getColumnExpressions() {
        return arguments;
    }

    private static Object constant(ColumnExpression argument) {
        if (argument instanceof ColumnFuncFormatExpression) return ((ColumnFuncFormatExpression) argument).getFormat();
        if (argument instanceof ColumnFuncValueExpression) return ((ColumnFuncValueExpression) argument).getValue();
        throw new IllegalArgumentException("HANA JSON paths require constant members and indexes");
    }

    private static String escapeMember(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c < 0x20) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else result.append(c);
        }
        return result.toString();
    }
}
