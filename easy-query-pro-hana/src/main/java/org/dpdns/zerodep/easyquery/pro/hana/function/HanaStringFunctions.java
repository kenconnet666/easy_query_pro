package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.def.AnySQLFunction;
import com.easy.query.core.func.def.impl.LengthSQLFunction;

import java.util.List;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;

final class HanaStringFunctions {
    private HanaStringFunctions() {
    }

    static SQLFunction length(List<ColumnExpression> arguments) {
        return new LengthSQLFunction(validated("length", arguments, 1)) {
            @Override
            public String sqlSegment(TableAvailable table) {
                // LENGTH 按 UTF-16 单元计数，正则字符匹配按 Unicode 码点计数，与 MySQL/PG 对齐。
                return codePointCount("TO_NVARCHAR({0})");
            }
        };
    }

    static SQLFunction indexOf(List<ColumnExpression> arguments) {
        String position = "LOCATE(TO_NVARCHAR({0}),{1})";
        String template = "CASE WHEN " + position + " = 0 THEN -1 ELSE OCCURRENCES_REGEXPR('.' FLAG 's' IN "
                + "SUBSTRING(TO_NVARCHAR({0}),1," + position + " - 1)) END";
        return new AnySQLFunction(template, validated("indexOf", arguments, 2));
    }

    static SQLFunction substring(List<ColumnExpression> arguments) {
        String text = "TO_NVARCHAR({0})";
        String start = codePointPosition(text, "{1}");
        String end = codePointPosition(text, "CAST({1} AS BIGINT) + CAST({2} AS BIGINT)");
        // 先定位码点边界，再用 HANA 的 UTF-16 下标截取，避免把代理对切成两个无效字符。
        String template = "CASE WHEN {2} = 0 OR " + start + " = 0 THEN SUBSTRING(" + text + ",1,0) ELSE SUBSTRING("
                + text + "," + start + ",COALESCE(NULLIF(" + end + ",0),LENGTH(" + text + ") + 1) - " + start + ") END";
        return new AnySQLFunction(template, validated("substring", arguments, 3));
    }

    static SQLFunction pad(List<ColumnExpression> arguments, boolean left) {
        if (arguments.size() < 2 || arguments.size() > 3) {
            throw new IllegalArgumentException("Padding requires a value, a width and an optional pattern");
        }
        String text = "TO_NVARCHAR({0})";
        String pattern = arguments.size() == 3 ? "TO_NVARCHAR({2})" : "' '";
        String missing = "GREATEST(CAST({1} AS BIGINT) - " + codePointCount(text) + ",0)";
        // RPAD 先重复完整模式，再按码点截取补齐部分；不能按 UTF-16 单元切开补充字符。
        String units = "CAST(CEIL(" + missing + " / NULLIF(" + codePointCount(pattern)
                + ",0)) * LENGTH(" + pattern + ") AS INTEGER)";
        String padding = prefix("RPAD(''," + units + "," + pattern + ")", missing);
        String padded = left ? padding + " || " + text : text + " || " + padding;
        String template = "CASE WHEN {1} <= " + codePointCount(text) + " THEN " + prefix(text, "{1}")
                + " WHEN LENGTH(" + pattern + ") = 0 THEN " + text + " ELSE (" + padded + ") END";
        return new AnySQLFunction(template, ImmutableCollections.listCopyOf(arguments));
    }

    private static String prefix(String text, String count) {
        return "SUBSTRING(" + text + ",1,CASE WHEN " + count + " <= 0 THEN 0 ELSE COALESCE(NULLIF("
                + codePointPosition(text, count) + ",0),LENGTH(" + text + ") + 1) - 1 END)";
    }

    private static String codePointPosition(String text, String index) {
        return "LOCATE_REGEXPR('.' FLAG 's' IN " + text + " OCCURRENCE LEAST(CAST((" + index
                + ") AS BIGINT) + 1,LENGTH(" + text + ") + 1))";
    }

    private static String codePointCount(String text) {
        return "OCCURRENCES_REGEXPR('.' FLAG 's' IN " + text + ")";
    }

    private static List<ColumnExpression> validated(String function, List<ColumnExpression> arguments, int count) {
        if (arguments.size() != count) throw new IllegalArgumentException(function + " requires " + count + " arguments");
        return ImmutableCollections.listCopyOf(arguments);
    }
}
