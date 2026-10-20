package org.dpdns.zerodep.easyquery.pro.dsl.hana.json;

import com.easy.query.core.expression.builder.AsSelector;
import com.easy.query.core.expression.builder.Selector;
import com.easy.query.core.expression.builder.GroupSelector;
import com.easy.query.core.expression.builder.OrderSelector;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.def.enums.OrderByModeEnum;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.SQLFunctionExpressionUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.AbstractJsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaLobSqlSupport;

import java.util.Objects;
import java.util.function.Function;

public final class HanaJsonTextExpressions {
    private HanaJsonTextExpressions() {
    }

    public static <T> HanaJsonTextExpression<T> view(ExpressionScope scope, ProDsl portable, PropTypeColumn<T> source, HanaJsonTextType type) {
        AbstractJsonExpression.requireView(scope, source, Objects.requireNonNull(type, "type"));
        return new Expression<>(scope, portable, source, type, factory -> scope.operand(source, factory));
    }

    public static HanaJsonTextExpression<String> value(ExpressionScope scope, ProDsl portable, JsonInput value, HanaJsonTextType type) {
        var source = AbstractJsonExpression.literal(scope, Objects.requireNonNull(type, "type"), value);
        return new Expression<>(scope, portable, source, type, factory -> scope.operand(source, factory));
    }

    public static HanaJsonTextExpression<String> cast(ExpressionScope scope, ProDsl portable, PropTypeColumn<?> source, HanaJsonTextType type) {
        var cast = AbstractJsonExpression.cast(scope, Objects.requireNonNull(type, "type"), source);
        return new Expression<>(scope, portable, cast, type, factory -> scope.operand(cast, factory));
    }

    private static final class Expression<T> extends AbstractJsonExpression<T> implements HanaJsonTextExpression<T> {
        private final boolean lob;

        private Expression(ExpressionScope scope, ProDsl portable, PropTypeColumn<T> source, HanaJsonTextType type, Function<SQLFunc, SQLFunction> function) {
            super(scope, portable, type, source, function);
            lob = type.usesLobStorage();
        }

        @Override
        public Function<SQLFunc, SQLFunction> func() {
            var function = super.func();
            return lob ? factory -> {
                var value = function.apply(factory);
                return scope.operation(HanaLobSqlSupport.lobResult(value), AggregationType.UNKNOWN, value);
            } : function;
        }

        @Override
        public void accept(Selector selector) {
            SQLFunctionExpressionUtil.accept(selector, getTable(), func());
        }

        @Override
        public void accept(AsSelector selector) {
            SQLFunctionExpressionUtil.accept(selector, getTable(), func());
        }

        @Override
        public void accept(GroupSelector selector) {
            if (lob) throw HanaLobSqlSupport.unsupportedClause("GROUP BY");
            super.accept(selector);
        }

        @Override
        public void accept(OrderSelector selector) {
            if (lob) throw HanaLobSqlSupport.unsupportedClause("ORDER BY");
            super.accept(selector);
        }

        @Override
        public void asc(boolean condition, OrderByModeEnum nulls) {
            if (lob && condition) throw HanaLobSqlSupport.unsupportedClause("ORDER BY");
            super.asc(condition, nulls);
        }

        @Override
        public void desc(boolean condition, OrderByModeEnum nulls) {
            if (lob && condition) throw HanaLobSqlSupport.unsupportedClause("ORDER BY");
            super.desc(condition, nulls);
        }
    }
}
