package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.def.AnySQLFunction;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class HanaAggregateFunctions {
    private HanaAggregateFunctions() {
    }

    static SQLFunction joining(List<ColumnExpression> arguments, boolean distinct) {
        if (arguments.size() < 2 || arguments.size() > 3) {
            throw new IllegalArgumentException("joining requires a separator, a value and optional ordering");
        }
        if (distinct) {
            throw new UnsupportedOperationException("HANA STRING_AGG requires duplicate elimination in the source query before joining");
        }
        String ordering = arguments.size() == 3 ? " ORDER BY {2}" : "";
        return new AnySQLFunction("STRING_AGG({1},{0}" + ordering + ")", List.copyOf(arguments));
    }

    static SQLFunction extremeColumns(List<ColumnExpression> arguments, boolean maximum) {
        if (arguments.isEmpty()) throw new IllegalArgumentException("Column extrema require at least one expression");
        if (arguments.size() == 1) return new AnySQLFunction("{0}", List.copyOf(arguments));
        // 原生极值传播 NULL；以首个非 NULL 值替代空项，既不改变极值，也保留全 NULL 的结果。
        // 不采用关联 UNION 子查询：revision 88 在同一投影组合两个此类子查询时触发优化器错误。
        String fallback = "COALESCE(" + IntStream.range(0, arguments.size())
                .mapToObj(i -> "{" + i + "}").collect(Collectors.joining(",")) + ")";
        String values = IntStream.range(0, arguments.size())
                .mapToObj(i -> "COALESCE({" + i + "}," + fallback + ")")
                .collect(Collectors.joining(","));
        return new AnySQLFunction((maximum ? "GREATEST(" : "LEAST(") + values + ")", List.copyOf(arguments));
    }
}
