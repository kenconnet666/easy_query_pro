package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.util.List;
import java.util.Objects;

/**
 * 采用 HANA、MySQL、PG 共同可用范围的 ROWS frame。
 * 结束位置不能早于开始位置；MySQL/PG 某些原生倒序空窗口不属于这个共同契约。
 * 调用时要求明确排序，防止“前 N 行”随执行计划改变；实际边缘行仍可产生 SQL NULL 结果。
 */
public record RowsFrame(RowBoundary start, RowBoundary end) implements WindowFrame {
    public RowsFrame {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.kind() == RowBoundary.Kind.UNBOUNDED_FOLLOWING || end.kind() == RowBoundary.Kind.UNBOUNDED_PRECEDING
                || start.position() > end.position())
            throw new IllegalArgumentException("Invalid ROWS frame: " + start.sql() + " to " + end.sql());
    }

    @Override
    public void validate(SqlDialect dialect, List<WindowOrder> ordering) {
        if (ordering.isEmpty()) throw new IllegalArgumentException("Portable ROWS frames require an explicit ordering");
    }

    @Override
    public SQLFunction function(SQLFunc factory, SqlDialect dialect) {
        return factory.anySQLFunction("ROWS BETWEEN " + start.sql() + " AND " + end.sql(), ignored -> {
        });
    }
}
