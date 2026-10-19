package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.column.impl.ColumnFuncValueExpressionImpl;
import com.easy.query.core.func.def.AnySQLFunction;
import com.easy.query.core.func.def.enums.DateTimeDurationEnum;
import com.easy.query.core.func.def.enums.DateTimeUnitEnum;
import com.easy.query.core.func.def.enums.TimeUnitEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class HanaDateTimeFunctions {
    private HanaDateTimeFunctions() {
    }

    static SQLFunction plus(List<ColumnExpression> arguments, TimeUnitEnum unit) {
        String template = switch (Objects.requireNonNull(unit, "unit")) {
            case MILLISECONDS -> "ADD_NANO100({0},{1} * 10000)";
            case SECONDS -> "ADD_SECONDS({0},{1})";
            case MINUTES -> "ADD_SECONDS({0},{1} * 60)";
            case HOURS -> "ADD_SECONDS({0},{1} * 3600)";
            case DAYS -> "ADD_DAYS({0},{1})";
            case MONTHS -> "ADD_MONTHS({0},{1})";
            case YEARS -> "ADD_YEARS({0},{1})";
        };
        return function(template, arguments, 2);
    }

    static SQLFunction plus(List<ColumnExpression> arguments, long amount, TimeUnit unit) {
        requireCount(arguments, 1);
        Objects.requireNonNull(unit, "unit");
        String template;
        long value;
        switch (unit) {
            case DAYS -> { template = "ADD_DAYS({0},{1})"; value = amount; }
            case HOURS -> { template = "ADD_SECONDS({0},{1})"; value = Math.multiplyExact(amount, 3600); }
            case MINUTES -> { template = "ADD_SECONDS({0},{1})"; value = Math.multiplyExact(amount, 60); }
            case SECONDS -> { template = "ADD_SECONDS({0},{1})"; value = amount; }
            case MILLISECONDS -> { template = "ADD_NANO100({0},{1})"; value = Math.multiplyExact(amount, 10_000); }
            case MICROSECONDS -> { template = "ADD_NANO100({0},{1})"; value = Math.multiplyExact(amount, 10); }
            case NANOSECONDS -> {
                // HANA TIMESTAMP 只有 100ns 精度，拒绝不能表示的增量，避免悄悄丢失数据。
                if (amount % 100 != 0) throw new IllegalArgumentException("HANA timestamp increments require 100ns precision");
                template = "ADD_NANO100({0},{1})";
                value = amount / 100;
            }
            default -> throw new IllegalArgumentException("Unsupported time unit: " + unit);
        }
        var parameters = new ArrayList<>(arguments);
        parameters.add(new ColumnFuncValueExpressionImpl(value));
        return function(template, parameters, 2);
    }

    static SQLFunction property(List<ColumnExpression> arguments, DateTimeUnitEnum unit) {
        String template = switch (Objects.requireNonNull(unit, "unit")) {
            case Year -> "YEAR({0})";
            case Month -> "MONTH({0})";
            case Day -> "DAYOFMONTH({0})";
            case Hour -> "HOUR({0})";
            case Minute -> "MINUTE({0})";
            case Second -> "FLOOR(SECOND({0}))";
            case DayOfYear -> "DAYOFYEAR({0})";
            case DayOfWeek -> "MOD(WEEKDAY({0}) + 1,7)";
            case DayOfWeekSunDayLastDay -> "(WEEKDAY({0}) + 1)";
        };
        return function(template, arguments, 1);
    }

    static SQLFunction duration(List<ColumnExpression> arguments, DateTimeDurationEnum unit, boolean forward) {
        long ticks = switch (Objects.requireNonNull(unit, "unit")) {
            case Days -> 864_000_000_000L;
            case Hours -> 36_000_000_000L;
            case Minutes -> 600_000_000L;
            case Seconds -> 10_000_000L;
        };
        String difference = forward ? "NANO100_BETWEEN({0},{1})" : "NANO100_BETWEEN({1},{0})";
        // 与上游 TIMESTAMPDIFF 保持一致：完整单位向零截断，负的小数秒不会变成 -1。
        return function("CAST(ROUND(" + difference + " / " + ticks + ",0,ROUND_DOWN) AS BIGINT)", arguments, 2);
    }

    private static SQLFunction function(String template, List<ColumnExpression> arguments, int count) {
        requireCount(arguments, count);
        return new AnySQLFunction(template, List.copyOf(arguments));
    }

    private static void requireCount(List<ColumnExpression> arguments, int count) {
        if (arguments.size() != count) {
            throw new IllegalArgumentException("HANA date function requires " + count + " arguments; received " + arguments.size());
        }
    }
}
