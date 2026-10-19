package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.enums.SQLLikeEnum;
import com.easy.query.core.expression.lambda.SQLActionExpression1;
import com.easy.query.core.func.SQLFuncImpl;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.column.ColumnFuncSelector;
import com.easy.query.core.func.def.AnySQLFunction;
import com.easy.query.core.func.def.enums.MathMethodEnum;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 只覆盖 HANA 与上游默认 SQL 不同的函数，参数与表达式组合继续由上游处理。 */
public final class HanaSQLFunc extends SQLFuncImpl {
    @Override
    public SQLFunction concat(List<ColumnExpression> expressions) {
        if (expressions.isEmpty()) throw new IllegalArgumentException("concat requires at least one argument");
        // HANA 的 CONCAT 只接受两个参数，连接运算符可保留任意参数数量与 NULL 传播。
        return new AnySQLFunction("(" + placeholders(expressions.size(), " || ") + ")", List.copyOf(expressions));
    }

    @Override
    public SQLFunction nullOrDefault(SQLActionExpression1<ColumnFuncSelector> arguments) {
        var expressions = getColumnExpressions(arguments);
        if (expressions.size() < 2) throw new IllegalArgumentException("coalesce requires at least two arguments");
        return new AnySQLFunction("COALESCE(" + placeholders(expressions.size(), ",") + ")", expressions);
    }

    @Override
    public SQLFunction indexOf(SQLActionExpression1<ColumnFuncSelector> arguments) {
        // HANA 的 LOCATE 参数顺序与 MySQL 相反；Proxy API 仍然返回从 0 开始的位置。
        return anySQLFunction("(LOCATE({0},{1}) - 1)", arguments);
    }

    @Override
    public SQLFunction length(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return new HanaLengthSQLFunction(getColumnExpressions(arguments));
    }

    @Override
    public SQLFunction like(SQLActionExpression1<ColumnFuncSelector> arguments, boolean like, SQLLikeEnum mode) {
        var function = new HanaLikeSQLFunction(getColumnExpressions(arguments), mode);
        return like ? function : not(selector -> selector.sqlFunc(function));
    }

    @Override
    public SQLFunction now() {
        return new AnySQLFunction("CURRENT_TIMESTAMP", List.of());
    }

    @Override
    public SQLFunction utcNow() {
        return new AnySQLFunction("CURRENT_UTCTIMESTAMP", List.of());
    }

    @Override
    public SQLFunction random() {
        return new AnySQLFunction("RAND()", List.of());
    }

    @Override
    public SQLFunction math(SQLActionExpression1<ColumnFuncSelector> arguments, MathMethodEnum method) {
        String template = switch (method) {
            case Log -> "LN({0})";
            case Log10 -> "LOG(10,{0})";
            case Pow -> "POWER({0},{1})";
            case Ceiling -> "CEIL({0})";
            // ROUND_DOWN 向零截断，FLOOR 对负数的结果不同；HANA 没有 TRUNC 函数。
            case Truncate -> "ROUND({0},0,ROUND_DOWN)";
            default -> null;
        };
        return template == null ? super.math(arguments, method) : anySQLFunction(template, arguments);
    }

    private static String placeholders(int count, String separator) {
        return IntStream.range(0, count).mapToObj(i -> "{" + i + "}").collect(Collectors.joining(separator));
    }
}
