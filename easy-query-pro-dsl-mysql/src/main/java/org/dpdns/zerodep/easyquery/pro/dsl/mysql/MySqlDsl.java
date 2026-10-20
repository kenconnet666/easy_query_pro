package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.AbstractJsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.MySqlJsonChange;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.MySqlJsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationType;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationExpressions;

import java.time.Duration;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.BiFunction;

public final class MySqlDsl {
    private final ExpressionScope scope;
    private final ProDsl portable;

    private MySqlDsl(ProxyEntity<?, ?> entity) {
        scope = new ExpressionScope(entity);
        if (scope.dialect() != SqlDialect.MYSQL) throw new IllegalArgumentException("MySqlDsl requires MySQL");
        portable = ProDsl.of(entity);
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
        AbstractJsonExpression.requireView(scope, source, Objects.requireNonNull(type, "type"));
        return new Json<>(scope, portable, source, factory -> scope.operand(source, factory));
    }

    public MySqlJsonExpression<String> nativeCast(PropTypeColumn<?> source, MySqlTypes.Json type) {
        var converted = AbstractJsonExpression.cast(scope, Objects.requireNonNull(type, "type"), source);
        return new Json<>(scope, portable, converted, factory -> scope.operand(converted, factory));
    }

    public MySqlJsonExpression<String> value(JsonInput input, MySqlTypes.Json type) {
        var value = AbstractJsonExpression.literal(scope, Objects.requireNonNull(type, "type"), input);
        return new Json<>(scope, portable, value, factory -> scope.operand(value, factory));
    }

    private static final class Json<T> extends AbstractJsonExpression<T> implements MySqlJsonExpression<T> {
        private Json(ExpressionScope scope, ProDsl portable, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
            super(scope, portable, MySqlTypes.json(), source, expression);
        }

        @Override
        public BooleanTypeExpression<Boolean> containsJson(JsonInput candidate) {
            var parameter = literal(scope, MySqlTypes.json(), candidate);
            return predicate((factory, document) -> factory.anySQLFunction("JSON_CONTAINS({0},{1})", args ->
                    args.sqlFunc(document).sqlFunc(scope.operand(parameter, factory))));
        }

        @Override
        public BooleanTypeExpression<Boolean> containsJson(JsonInput candidate, JsonPath path) {
            var parameter = literal(scope, MySqlTypes.json(), candidate);
            String checked = Objects.requireNonNull(path, "path").toSqlJsonPath();
            return predicate((factory, document) -> factory.anySQLFunction("JSON_CONTAINS({0},{1},CONVERT({2} USING utf8mb4))", args ->
                    args.sqlFunc(document).sqlFunc(scope.operand(parameter, factory)).value(checked)));
        }

        @Override
        public BooleanTypeExpression<Boolean> containsAnyPath(JsonPath... paths) {
            return containsPaths("one", paths);
        }

        @Override
        public BooleanTypeExpression<Boolean> containsAllPaths(JsonPath... paths) {
            return containsPaths("all", paths);
        }

        private BooleanTypeExpression<Boolean> containsPaths(String mode, JsonPath[] paths) {
            var checked = checkedPaths(paths);
            String template = "JSON_CONTAINS_PATH({0},'" + mode + "'" + pathArguments(checked.size()) + ")";
            return predicate((factory, document) -> factory.anySQLFunction(template, args -> {
                args.sqlFunc(document);
                checked.forEach(path -> args.value(path.toSqlJsonPath()));
            }));
        }

        @Override
        public MySqlJsonExpression<T> setPath(JsonPath path, JsonInput value) {
            return setPaths(new MySqlJsonChange(path, value));
        }

        @Override
        public MySqlJsonExpression<T> setPaths(MySqlJsonChange... changes) {
            var checked = List.of(Objects.requireNonNull(changes, "changes"));
            if (checked.isEmpty()) throw new IllegalArgumentException("JSON_SET requires at least one change");
            var values = checked.stream().map(change -> literal(scope, MySqlTypes.json(), change.value())).toList();
            var template = new StringBuilder("JSON_SET({0}");
            for (int i = 0; i < checked.size(); i++) {
                template.append(",CONVERT({").append(2 * i + 1).append("} USING utf8mb4),{").append(2 * i + 2).append('}');
            }
            template.append(')');
            return new Json<>(scope, portable, this, factory -> scope.scalar(factory, this, document -> factory.anySQLFunction(template.toString(), args -> {
                args.sqlFunc(document);
                for (int i = 0; i < checked.size(); i++) {
                    args.value(checked.get(i).path().toSqlJsonPath()).sqlFunc(scope.operand(values.get(i), factory));
                }
            })));
        }

        @Override
        public MySqlJsonExpression<T> removePath(JsonPath path) {
            return removePaths(path);
        }

        @Override
        public MySqlJsonExpression<T> removePaths(JsonPath... paths) {
            var checked = checkedPaths(paths);
            if (checked.stream().anyMatch(path -> path.segments().isEmpty())) {
                throw new IllegalArgumentException("JSON_REMOVE cannot remove the document root");
            }
            String template = "JSON_REMOVE({0}" + pathArguments(checked.size()) + ")";
            return new Json<>(scope, portable, this, factory -> scope.scalar(factory, this, document -> factory.anySQLFunction(template, args -> {
                args.sqlFunc(document);
                checked.forEach(path -> args.value(path.toSqlJsonPath()));
            })));
        }

        private BooleanTypeExpression<Boolean> predicate(BiFunction<SQLFunc, SQLFunction, SQLFunction> expression) {
            return new BooleanTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.scalar(factory, this, document -> expression.apply(factory, document)), Boolean.class);
        }

        private static List<JsonPath> checkedPaths(JsonPath[] paths) {
            var checked = List.of(Objects.requireNonNull(paths, "paths"));
            if (checked.isEmpty()) throw new IllegalArgumentException("At least one JSON path is required");
            return checked;
        }

        private static String pathArguments(int count) {
            var arguments = new StringBuilder();
            for (int i = 1; i <= count; i++) arguments.append(",CONVERT({").append(i).append("} USING utf8mb4)");
            return arguments.toString();
        }
    }
}
