package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.AbstractJsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.AbstractMySqlJsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.MySqlJsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationType;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationExpressions;

import java.time.Duration;

import java.util.Objects;
import java.util.function.Function;

public class MySqlDsl extends ProDsl {

    protected MySqlDsl(ProxyEntity<?, ?> entity) {
        this(new ExpressionScope(entity));
    }

    protected MySqlDsl(ExpressionScope scope) {
        super(scope);
        if (scope.dialect() != SqlDialect.MYSQL) throw new IllegalArgumentException("MySqlDsl requires MySQL");
    }

    public static MySqlDsl of(ProxyEntity<?, ?> entity) {
        return new MySqlDsl(entity);
    }

    public <T> MySqlDurationExpression<T> view(PropTypeColumn<T> source, MySqlDurationType type) {
        return MySqlDurationExpressions.view(scope, source, type);
    }

    public MySqlDurationExpression<Duration> value(Duration value, MySqlDurationType type) {
        return MySqlDurationExpressions.value(scope, value, type);
    }

    public MySqlDurationExpression<Duration> nativeCast(PropTypeColumn<?> source, MySqlDurationType type) {
        return MySqlDurationExpressions.cast(scope, source, type);
    }

    public <N extends Number> NumberTypeExpression<N> value(N value, MySqlTypes.Unsigned<N> type) {
        Objects.requireNonNull(type, "type");
        N checked = type.prepareValue(value);
        // The checked parameter fits its declared storage width; native UNSIGNED CAST itself is always 64-bit.
        return new NumberTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                factory -> scope.function(factory.anySQLFunction("CAST({0} AS UNSIGNED)", args -> args.value(checked)), AggregationType.UNKNOWN, false), type.javaType());
    }

    public <T> MySqlJsonExpression<T> view(PropTypeColumn<T> source, MySqlTypes.Json type) {
        Objects.requireNonNull(type, "type");
        return new Json<>(scope, this, source, factory -> scope.operand(source, factory));
    }

    public MySqlJsonExpression<String> nativeCast(PropTypeColumn<?> source, MySqlTypes.Json type) {
        var converted = AbstractJsonExpression.cast(scope, Objects.requireNonNull(type, "type"), source);
        return new Json<>(scope, this, converted, factory -> scope.operand(converted, factory));
    }

    public MySqlJsonExpression<String> value(JsonInput input, MySqlTypes.Json type) {
        var value = AbstractJsonExpression.literal(scope, Objects.requireNonNull(type, "type"), input);
        return new Json<>(scope, this, value, factory -> scope.operand(value, factory));
    }

    private static final class Json<T> extends AbstractMySqlJsonExpression<T, Json<T>> {
        private Json(ExpressionScope scope, ProDsl portable, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
            super(scope, portable, source, expression);
        }

        @Override
        protected Json<T> recreate(PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
            return new Json<>(scope, portable, source, expression);
        }
    }
}
