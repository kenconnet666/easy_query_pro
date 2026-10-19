package org.dpdns.zerodep.easyquery.pro.hana.function;

import com.easy.query.core.common.LikeFunctionTemplateGenerator;
import com.easy.query.core.enums.SQLLikeEnum;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.def.impl.AbstractLikeSQLFunction;

import java.util.ArrayList;
import java.util.List;

final class HanaLikeSQLFunction extends AbstractLikeSQLFunction {
    private final List<ColumnExpression> expressions;
    private final String template;

    HanaLikeSQLFunction(List<ColumnExpression> arguments, SQLLikeEnum mode) {
        var prepared = new ArrayList<>(arguments);
        var generator = new LikeFunctionTemplateGenerator(prepared, mode, this::getColumnFuncValueExpression,
                "{0} LIKE ({1} || '%') ESCAPE '!'",
                "{0} LIKE ('%' || {1}) ESCAPE '!'",
                "{0} LIKE ('%' || {1} || '%') ESCAPE '!'",
                "{0} LIKE ({1} || '%')",
                "{0} LIKE ('%' || {1})",
                "{0} LIKE ('%' || {1} || '%')") {
            @Override
            protected String escape(String value) {
                return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
            }
        };
        // 上游会改写待绑定值；构造时在私有副本上完成，避免多次渲染重复转义。
        template = generator.sqlSegment(null);
        expressions = List.copyOf(prepared);
    }

    @Override
    public String sqlSegment(TableAvailable table) {
        return template;
    }

    @Override
    public int paramMarks() {
        return expressions.size();
    }

    @Override
    protected List<ColumnExpression> getColumnExpressions() {
        return expressions;
    }
}
