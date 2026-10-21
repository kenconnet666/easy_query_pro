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
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;

final class HanaDateTimeFunctions {
    private HanaDateTimeFunctions() {
    }

    static SQLFunction plus(List<ColumnExpression> arguments, TimeUnitEnum unit) {
        String template;
        switch (Objects.requireNonNull(unit, "unit")) {
            case MILLISECONDS: template = "ADD_NANO100({0},{1} * 10000)"; break;
            case SECONDS: template = "ADD_SECONDS({0},{1})"; break;
            case MINUTES: template = "ADD_SECONDS({0},{1} * 60)"; break;
            case HOURS: template = "ADD_SECONDS({0},{1} * 3600)"; break;
            case DAYS: template = "ADD_DAYS({0},{1})"; break;
            case MONTHS: template = "ADD_MONTHS({0},{1})"; break;
            case YEARS: template = "ADD_YEARS({0},{1})"; break;
            default: throw new IllegalStateException("Unhandled value: " + Objects.requireNonNull(unit, "unit"));
        }
        return function(template, arguments, 2);
    }

    static SQLFunction plus(List<ColumnExpression> arguments, long amount, TimeUnit unit) {
        requireCount(arguments, 1);
        Objects.requireNonNull(unit, "unit");
        String template;
        long value;
        switch (unit) {
            case DAYS: { template = "ADD_DAYS({0},{1})"; value = amount; } break;
            case HOURS: { template = "ADD_SECONDS({0},{1})"; value = Math.multiplyExact(amount, 3600); } break;
            case MINUTES: { template = "ADD_SECONDS({0},{1})"; value = Math.multiplyExact(amount, 60); } break;
            case SECONDS: { template = "ADD_SECONDS({0},{1})"; value = amount; } break;
            case MILLISECONDS: { template = "ADD_NANO100({0},{1})"; value = Math.multiplyExact(amount, 10_000); } break;
            case MICROSECONDS: { template = "ADD_NANO100({0},{1})"; value = Math.multiplyExact(amount, 10); } break;
            case NANOSECONDS: {
                // HANA TIMESTAMP 只有 100ns 精度，拒绝不能表示的增量，避免悄悄丢失数据。
                if (amount % 100 != 0) throw new IllegalArgumentException("HANA timestamp increments require 100ns precision");
                template = "ADD_NANO100({0},{1})";
                value = amount / 100;
            } break;
            default: throw new IllegalArgumentException("Unsupported time unit: " + unit);
        }
        ArrayList<ColumnExpression> parameters = new ArrayList<>(arguments);
        parameters.add(new ColumnFuncValueExpressionImpl(value));
        return function(template, parameters, 2);
    }

    static SQLFunction property(List<ColumnExpression> arguments, DateTimeUnitEnum unit) {
        String template;
        switch (Objects.requireNonNull(unit, "unit")) {
            case Year: template = "YEAR({0})"; break;
            case Month: template = "MONTH({0})"; break;
            case Day: template = "DAYOFMONTH({0})"; break;
            case Hour: template = "HOUR({0})"; break;
            case Minute: template = "MINUTE({0})"; break;
            case Second: template = "FLOOR(SECOND({0}))"; break;
            case DayOfYear: template = "DAYOFYEAR({0})"; break;
            case DayOfWeek: template = "MOD(WEEKDAY({0}) + 1,7)"; break;
            case DayOfWeekSunDayLastDay: template = "(WEEKDAY({0}) + 1)"; break;
            default: throw new IllegalStateException("Unhandled value: " + Objects.requireNonNull(unit, "unit"));
        }
        return function(template, arguments, 1);
    }

    static SQLFunction duration(List<ColumnExpression> arguments, DateTimeDurationEnum unit, boolean forward) {
        long ticks;
        switch (Objects.requireNonNull(unit, "unit")) {
            case Days: ticks = 864_000_000_000L; break;
            case Hours: ticks = 36_000_000_000L; break;
            case Minutes: ticks = 600_000_000L; break;
            case Seconds: ticks = 10_000_000L; break;
            default: throw new IllegalStateException("Unhandled value: " + Objects.requireNonNull(unit, "unit"));
        }
        String difference = forward ? "NANO100_BETWEEN({0},{1})" : "NANO100_BETWEEN({1},{0})";
        // 与上游 TIMESTAMPDIFF 保持一致：完整单位向零截断，负的小数秒不会变成 -1。
        return function("CAST(ROUND(" + difference + " / " + ticks + ",0,ROUND_DOWN) AS BIGINT)", arguments, 2);
    }

    private static SQLFunction function(String template, List<ColumnExpression> arguments, int count) {
        requireCount(arguments, count);
        return new AnySQLFunction(template, ImmutableCollections.listCopyOf(arguments));
    }

    private static void requireCount(List<ColumnExpression> arguments, int count) {
        if (arguments.size() != count) {
            throw new IllegalArgumentException("HANA date function requires " + count + " arguments; received " + arguments.size());
        }
    }
}
