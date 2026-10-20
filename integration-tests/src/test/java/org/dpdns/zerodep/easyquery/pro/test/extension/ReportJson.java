package org.dpdns.zerodep.easyquery.pro.test.extension;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.AbstractMySqlJsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

import java.util.function.Function;

/**
 * Consumer-owned extension used by compilation and real database contracts.
 */
public class ReportJson<T> extends AbstractMySqlJsonExpression<T, ReportJson<T>> {
    public ReportJson(ExpressionScope scope, ProDsl portable, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
        super(scope, portable, source, expression);
    }

    @Override
    protected ReportJson<T> recreate(PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
        return new ReportJson<>(scope, portable, source, expression);
    }

    public BooleanTypeExpression<Boolean> hasName() {
        return containsAllPaths(JsonPath.root().key("name"));
    }
}
