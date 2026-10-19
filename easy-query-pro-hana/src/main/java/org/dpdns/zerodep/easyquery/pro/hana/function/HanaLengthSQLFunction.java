package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.def.impl.LengthSQLFunction;

import java.util.List;

final class HanaLengthSQLFunction extends LengthSQLFunction {
    HanaLengthSQLFunction(List<ColumnExpression> expressions) {
        super(expressions);
        if (expressions.size() != 1) throw new IllegalArgumentException("length requires one argument");
    }

    @Override
    public String sqlSegment(TableAvailable table) {
        return "LENGTH({0})";
    }
}
