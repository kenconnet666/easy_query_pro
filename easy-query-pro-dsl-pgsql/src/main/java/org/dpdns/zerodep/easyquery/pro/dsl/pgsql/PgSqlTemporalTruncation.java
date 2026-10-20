package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;

import java.util.Locale;

final class PgSqlTemporalTruncation implements TemporalTruncation {
    @Override
    public SQLFunction truncate(ColumnExpression source, TruncationUnit unit, boolean dateOnly) {
        String field = unit == TruncationUnit.ISO_WEEK ? "week" : unit.name().toLowerCase(Locale.ROOT);
        // DATE 的重载推断可能走 timestamptz；显式选择无时区输入，避免会话时区改变日历截断。
        var template = new Template("date_trunc('" + field + "',CAST({0} AS TIMESTAMP WITHOUT TIME ZONE))", 1);
        return (dateOnly ? template.castTo("DATE") : template).bind(source);
    }
}
