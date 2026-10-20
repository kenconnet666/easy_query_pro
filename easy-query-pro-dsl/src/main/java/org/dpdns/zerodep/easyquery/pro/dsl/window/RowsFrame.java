package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.util.List;
import java.util.Objects;

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
