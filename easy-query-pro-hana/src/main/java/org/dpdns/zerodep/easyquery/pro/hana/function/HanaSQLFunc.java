package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.enums.SQLLikeEnum;
import com.easy.query.core.expression.lambda.SQLActionExpression1;
import com.easy.query.core.expression.parser.core.SQLTableOwner;
import com.easy.query.core.func.SQLFuncImpl;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.column.ColumnFuncSelector;
import com.easy.query.core.func.column.impl.ColumnFuncFormatExpressionImpl;
import com.easy.query.core.func.def.AnySQLFunction;
import com.easy.query.core.func.def.enums.MathMethodEnum;
import com.easy.query.core.func.def.enums.DateTimeDurationEnum;
import com.easy.query.core.func.def.enums.DateTimeUnitEnum;
import com.easy.query.core.func.def.enums.TimeUnitEnum;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.hana.json.HanaJsonSupport;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;

/** 只覆盖 HANA 与上游默认 SQL 不同的函数，参数与表达式组合继续由上游处理。 */
public class HanaSQLFunc extends SQLFuncImpl {
    private final HanaOptions options;

    public HanaSQLFunc() {
        this(HanaOptions.DEFAULT);
    }

    public HanaSQLFunc(HanaOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public SQLFunction concat(List<ColumnExpression> expressions) {
        if (expressions.isEmpty()) throw new IllegalArgumentException("concat requires at least one argument");
        // HANA 的 CONCAT 只接受两个参数，连接运算符可保留任意参数数量与 NULL 传播。
        return new AnySQLFunction("(" + placeholders(expressions.size(), " || ") + ")", ImmutableCollections.listCopyOf(expressions));
    }

    @Override
    public SQLFunction nullOrDefault(SQLActionExpression1<ColumnFuncSelector> arguments) {
        List<ColumnExpression> expressions = getColumnExpressions(arguments);
        if (expressions.size() < 2) throw new IllegalArgumentException("coalesce requires at least two arguments");
        return new AnySQLFunction("COALESCE(" + placeholders(expressions.size(), ",") + ")", expressions);
    }

    @Override
    public SQLFunction indexOf(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaStringFunctions.indexOf(getColumnExpressions(arguments));
    }

    @Override
    public SQLFunction length(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaStringFunctions.length(getColumnExpressions(arguments));
    }

    @Override
    public SQLFunction subString(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaStringFunctions.substring(getColumnExpressions(arguments));
    }

    @Override
    public SQLFunction leftPad(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaStringFunctions.pad(getColumnExpressions(arguments), true);
    }

    @Override
    public SQLFunction rightPad(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaStringFunctions.pad(getColumnExpressions(arguments), false);
    }

    @Override
    public SQLFunction empty(SQLActionExpression1<ColumnFuncSelector> arguments) {
        // HANA 的字符比较忽略尾随空格，不能用 = '' 判断长度为零。
        return anySQLFunction("({0} IS NULL OR LENGTH(TO_NVARCHAR({0})) = 0)", arguments);
    }

    @Override
    public SQLFunction notEmpty(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return anySQLFunction("({0} IS NOT NULL AND LENGTH(TO_NVARCHAR({0})) > 0)", arguments);
    }

    @Override
    public SQLFunction joining(SQLActionExpression1<ColumnFuncSelector> arguments, boolean distinct) {
        return HanaAggregateFunctions.joining(getColumnExpressions(arguments), distinct);
    }

    @Override
    public SQLFunction maxColumns(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaAggregateFunctions.extremeColumns(getColumnExpressions(arguments), true);
    }

    @Override
    public SQLFunction minColumns(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaAggregateFunctions.extremeColumns(getColumnExpressions(arguments), false);
    }

    @Override
    public SQLFunction like(SQLActionExpression1<ColumnFuncSelector> arguments, boolean like, SQLLikeEnum mode) {
        HanaLikeSQLFunction function = new HanaLikeSQLFunction(getColumnExpressions(arguments), mode);
        return like ? function : not(selector -> selector.sqlFunc(function));
    }

    @Override
    public SQLFunction now() {
        return new AnySQLFunction("CURRENT_TIMESTAMP", ImmutableCollections.listOf());
    }

    @Override
    public SQLFunction utcNow() {
        return new AnySQLFunction("CURRENT_UTCTIMESTAMP", ImmutableCollections.listOf());
    }

    @Override
    public SQLFunction random() {
        return new AnySQLFunction("RAND()", ImmutableCollections.listOf());
    }

    @Override
    public SQLFunction booleanSQLFunction(String predicate, SQLActionExpression1<ColumnFuncSelector> arguments) {
        SQLFunction function = anySQLFunction(predicate, arguments);
        // HANA 不能直接投影谓词；第二个 WHEN 保留 UNKNOWN，而不是把 SQL NULL 折成 false。
        return anySQLFunction("(CASE WHEN {0} THEN TRUE WHEN NOT ({0}) THEN FALSE ELSE NULL END)",
                args -> args.sqlFunc(function));
    }

    @Override
    public SQLFunction math(SQLActionExpression1<ColumnFuncSelector> arguments, MathMethodEnum method) {
        String template;
        switch (method) {
            case Log: template = "LN({0})"; break;
            case Log10: template = "LOG(10,{0})"; break;
            case Pow: template = "POWER({0},{1})"; break;
            case Ceiling: template = "CEIL({0})"; break;
            case Truncate: template = "ROUND({0},0,ROUND_DOWN)"; break;
            default: template = null; break;
        }
        return template == null ? super.math(arguments, method) : anySQLFunction(template, arguments);
    }

    @Override
    public SQLFunction plusDateTime(SQLActionExpression1<ColumnFuncSelector> arguments, long duration, TimeUnit unit) {
        return HanaDateTimeFunctions.plus(getColumnExpressions(arguments), duration, unit);
    }

    @Override
    public SQLFunction plusDateTime2(SQLActionExpression1<ColumnFuncSelector> arguments, TimeUnitEnum unit) {
        return HanaDateTimeFunctions.plus(getColumnExpressions(arguments), unit);
    }

    @Override
    public SQLFunction plusDateTimeMonths(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaDateTimeFunctions.plus(getColumnExpressions(arguments), TimeUnitEnum.MONTHS);
    }

    @Override
    public SQLFunction plusDateTimeYears(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaDateTimeFunctions.plus(getColumnExpressions(arguments), TimeUnitEnum.YEARS);
    }

    @Override
    public SQLFunction dateTimeProperty(SQLActionExpression1<ColumnFuncSelector> arguments, DateTimeUnitEnum unit) {
        return HanaDateTimeFunctions.property(getColumnExpressions(arguments), unit);
    }

    @Override
    public SQLFunction duration(SQLActionExpression1<ColumnFuncSelector> arguments, DateTimeDurationEnum unit) {
        return HanaDateTimeFunctions.duration(getColumnExpressions(arguments), unit, false);
    }

    @Override
    public SQLFunction duration2(SQLActionExpression1<ColumnFuncSelector> arguments, DateTimeDurationEnum unit) {
        return HanaDateTimeFunctions.duration(getColumnExpressions(arguments), unit, true);
    }

    @Override
    public SQLFunction dateTimeFormat(SQLActionExpression1<ColumnFuncSelector> arguments, String javaFormat) {
        return HanaDateTimeFormat.format(getColumnExpressions(arguments), javaFormat);
    }

    @Override
    public SQLFunction dateTimeSQLFormat(SQLTableOwner owner, String property, String format) {
        Objects.requireNonNull(format, "format");
        return anySQLFunction("TO_NVARCHAR({0},{1})", args -> args.column(getTable(owner), property).value(format));
    }

    @Override
    public SQLFunction cast(SQLActionExpression1<ColumnFuncSelector> arguments, Class<?> target) {
        return HanaCastFunctions.cast(getColumnExpressions(arguments), target);
    }

    @Override
    public SQLFunction jsonObjectField(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaJsonFunction.object(getColumnExpressions(arguments), options, false);
    }

    @Override
    public SQLFunction jsonObjectExtract(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaJsonFunction.object(getColumnExpressions(arguments), options, true);
    }

    @Override
    public SQLFunction jsonObjectContainsKey(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaJsonFunction.object(getColumnExpressions(arguments), options, true).containsKey();
    }

    @Override
    public SQLFunction jsonArrayByIndex(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaJsonFunction.array(getColumnExpressions(arguments), options, false);
    }

    @Override
    public SQLFunction jsonArrayExtractByIndex(SQLActionExpression1<ColumnFuncSelector> arguments) {
        return HanaJsonFunction.array(getColumnExpressions(arguments), options, true);
    }

    @Override
    public SQLFunction jsonArrayLength(SQLActionExpression1<ColumnFuncSelector> arguments) {
        List<ColumnExpression> expressions = getColumnExpressions(arguments);
        if (expressions.size() != 1) throw new IllegalArgumentException("JSON array length requires one document");
        if (options.jsonSupportSchema() == null) {
            throw new IllegalStateException("HANA JSON array length requires explicit HanaJsonSupport deployment and jsonSupportSchema configuration");
        }
        return new AnySQLFunction("{1}({0})", ImmutableCollections.listOf(expressions.get(0),
                new ColumnFuncFormatExpressionImpl(HanaJsonSupport.arrayLengthFunctionName(options.jsonSupportSchema()))));
    }

    private static String placeholders(int count, String separator) {
        return IntStream.range(0, count).mapToObj(i -> "{" + i + "}").collect(Collectors.joining(separator));
    }
}
