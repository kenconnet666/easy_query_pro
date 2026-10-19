package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.def.AnySQLFunction;
import com.easy.query.core.func.def.impl.LengthSQLFunction;

import java.util.List;

final class HanaStringFunctions {
    private HanaStringFunctions() {
    }

    static SQLFunction length(List<ColumnExpression> arguments) {
        return new LengthSQLFunction(validated("length", arguments, 1)) {
            @Override
            public String sqlSegment(TableAvailable table) {
                // LENGTH 按 UTF-16 单元计数，正则字符匹配按 Unicode 码点计数，与 MySQL/PG 对齐。
                return "OCCURRENCES_REGEXPR('.' FLAG 's' IN TO_NVARCHAR({0}))";
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
        String start = "LOCATE_REGEXPR('.' FLAG 's' IN " + text
                + " OCCURRENCE LEAST(CAST({1} AS BIGINT) + 1,LENGTH(" + text + ") + 1))";
        String end = "LOCATE_REGEXPR('.' FLAG 's' IN " + text
                + " OCCURRENCE LEAST(CAST({1} AS BIGINT) + CAST({2} AS BIGINT) + 1,LENGTH(" + text + ") + 1))";
        // 先定位码点边界，再用 HANA 的 UTF-16 下标截取，避免把代理对切成两个无效字符。
        String template = "CASE WHEN {2} = 0 OR " + start + " = 0 THEN SUBSTRING(" + text + ",1,0) ELSE SUBSTRING("
                + text + "," + start + ",COALESCE(NULLIF(" + end + ",0),LENGTH(" + text + ") + 1) - " + start + ") END";
        return new AnySQLFunction(template, validated("substring", arguments, 3));
    }

    private static List<ColumnExpression> validated(String function, List<ColumnExpression> arguments, int count) {
        if (arguments.size() != count) throw new IllegalArgumentException(function + " requires " + count + " arguments");
        return List.copyOf(arguments);
    }
}
