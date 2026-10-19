package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.column.impl.ColumnFuncValueExpressionImpl;
import com.easy.query.core.func.def.AnySQLFunction;

import java.util.ArrayList;
import java.util.List;

/** 将选定的 Java 数字日期模式翻译为 SQL，普通文本始终作为绑定值。 */
final class HanaDateTimeFormat {
    private HanaDateTimeFormat() {
    }

    static SQLFunction format(List<ColumnExpression> arguments, String pattern) {
        if (arguments.size() != 1) throw new IllegalArgumentException("Date formatting requires one argument");
        String format = pattern == null ? "yyyy-MM-dd HH:mm:ss.SSSSSS" : pattern;
        var parameters = new ArrayList<>(arguments);
        var parts = new ArrayList<String>();
        var literal = new StringBuilder();
        boolean quoted = false;
        boolean hasDatePart = false;
        for (int i = 0; i < format.length();) {
            char current = format.charAt(i);
            if (current == '\'') {
                if (i + 1 < format.length() && format.charAt(i + 1) == '\'') {
                    literal.append('\'');
                    i += 2;
                } else {
                    quoted = !quoted;
                    i++;
                }
            } else if (!quoted && isPatternLetter(current)) {
                appendLiteral(literal, parameters, parts);
                int end = i + 1;
                while (end < format.length() && format.charAt(end) == current) end++;
                parts.add(token(format.substring(i, end)));
                hasDatePart = true;
                i = end;
            } else {
                literal.append(current);
                i++;
            }
        }
        if (quoted) throw new IllegalArgumentException("Unterminated quoted date format literal: " + pattern);
        appendLiteral(literal, parameters, parts);
        if (parts.isEmpty()) {
            parameters.add(new ColumnFuncValueExpressionImpl(""));
            parts.add("{1}");
        }
        String expression = "(" + String.join(" || ", parts) + ")";
        if (!hasDatePart) expression = "CASE WHEN {0} IS NULL THEN NULL ELSE " + expression + " END";
        return new AnySQLFunction(expression, List.copyOf(parameters));
    }

    private static String token(String token) {
        return switch (token) {
            case "yyyy" -> formatted("YYYY");
            case "yy" -> formatted("YY");
            case "y" -> "TO_NVARCHAR(YEAR({0}))";
            case "MM" -> formatted("MM");
            case "M" -> "TO_NVARCHAR(MONTH({0}))";
            case "dd" -> formatted("DD");
            case "d" -> "TO_NVARCHAR(DAYOFMONTH({0}))";
            case "HH" -> formatted("HH24");
            case "H" -> "TO_NVARCHAR(HOUR({0}))";
            case "hh" -> formatted("HH12");
            case "h" -> "TO_NVARCHAR(TO_INTEGER(" + formatted("HH12") + "))";
            case "mm" -> formatted("MI");
            case "m" -> "TO_NVARCHAR(MINUTE({0}))";
            case "ss" -> formatted("SS");
            case "s" -> "TO_NVARCHAR(CAST(FLOOR(SECOND({0})) AS INTEGER))";
            case "a", "tt" -> formatted("AM");
            case "t" -> "SUBSTRING(" + formatted("AM") + ",1,1)";
            default -> fractionalSeconds(token);
        };
    }

    private static String fractionalSeconds(String token) {
        if (token.length() > 9 || token.chars().anyMatch(c -> c != 'S')) {
            throw new IllegalArgumentException("Unsupported HANA Java date format token: " + token);
        }
        int digits = Math.min(7, token.length());
        String expression = formatted("FF" + digits);
        // Java 可输出九位小数；HANA 只有七位，最后两位只能补零，不能声称存在额外精度。
        return token.length() <= 7 ? expression : "(" + expression + " || '" + "0".repeat(token.length() - 7) + "')";
    }

    private static String formatted(String mask) {
        return "TO_NVARCHAR({0},'" + mask + "')";
    }

    private static boolean isPatternLetter(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private static void appendLiteral(StringBuilder literal, List<ColumnExpression> parameters, List<String> parts) {
        if (literal.isEmpty()) return;
        parts.add("{" + parameters.size() + "}");
        parameters.add(new ColumnFuncValueExpressionImpl(literal.toString()));
        literal.setLength(0);
    }
}
