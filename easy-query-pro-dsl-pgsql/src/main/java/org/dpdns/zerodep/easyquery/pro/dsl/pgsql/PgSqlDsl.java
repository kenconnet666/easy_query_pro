package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.AbstractJsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.*;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayExpressions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayType;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayValue;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgIntervalType;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgIntervalExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgIntervalExpressions;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class PgSqlDsl {
    private final ExpressionScope scope;
    private final ProDsl portable;

    private PgSqlDsl(ProxyEntity<?, ?> entity) {
        scope = new ExpressionScope(entity);
        PgSqlTypes.json().requireDialect(scope.dialect());
        portable = ProDsl.of(entity);
    }

    public static PgSqlDsl of(ProxyEntity<?, ?> entity) {
        return new PgSqlDsl(entity);
    }

    public <T> PgIntervalExpression<T> view(PropTypeColumn<T> source, PgIntervalType type) {
        Objects.requireNonNull(type, "type");
        return PgIntervalExpressions.view(scope, source);
    }

    public PgIntervalExpression<PgInterval> value(PgInterval value, PgIntervalType type) {
        Objects.requireNonNull(type, "type");
        return PgIntervalExpressions.value(scope, value);
    }

    public PgIntervalExpression<PgInterval> nativeCast(PropTypeColumn<?> source, PgIntervalType type) {
        Objects.requireNonNull(type, "type");
        return PgIntervalExpressions.cast(scope, source);
    }

    public <E, T> PgArrayExpression<E, T> view(PropTypeColumn<T> source, PgArrayType<E> type) {
        return PgArrayExpressions.view(scope, source, type);
    }

    public <E> PgArrayExpression<E, PgArrayValue<E>> value(List<? extends E> values, PgArrayType<E> type) {
        return PgArrayExpressions.value(scope, values, type);
    }

    public <E> PgArrayExpression<E, PgArrayValue<E>> nativeCast(PropTypeColumn<?> source, PgArrayType<E> type) {
        return PgArrayExpressions.cast(scope, source, type);
    }

    public <T> PgJsonExpression<T> view(PropTypeColumn<T> source, PgSqlTypes.Json type) {
        AbstractJsonExpression.requireView(scope, source, Objects.requireNonNull(type, "type"));
        return new Json<>(scope, portable, source, factory -> scope.operand(source, factory));
    }

    public <T> PgJsonbExpression<T> view(PropTypeColumn<T> source, PgSqlTypes.Jsonb type) {
        AbstractJsonExpression.requireView(scope, source, Objects.requireNonNull(type, "type"));
        return new Jsonb<>(scope, portable, source, factory -> scope.operand(source, factory));
    }

    public PgJsonExpression<String> nativeCast(PropTypeColumn<?> source, PgSqlTypes.Json type) {
        var converted = AbstractJsonExpression.cast(scope, Objects.requireNonNull(type, "type"), source);
        return new Json<>(scope, portable, converted, factory -> scope.operand(converted, factory));
    }

    public PgJsonbExpression<String> nativeCast(PropTypeColumn<?> source, PgSqlTypes.Jsonb type) {
        var converted = AbstractJsonExpression.cast(scope, Objects.requireNonNull(type, "type"), source);
        return new Jsonb<>(scope, portable, converted, factory -> scope.operand(converted, factory));
    }

    public PgJsonExpression<String> value(JsonInput input, PgSqlTypes.Json type) {
        var value = AbstractJsonExpression.literal(scope, Objects.requireNonNull(type, "type"), input);
        return new Json<>(scope, portable, value, factory -> scope.operand(value, factory));
    }

    public PgJsonbExpression<String> value(JsonInput input, PgSqlTypes.Jsonb type) {
        var value = AbstractJsonExpression.literal(scope, Objects.requireNonNull(type, "type"), input);
        return new Jsonb<>(scope, portable, value, factory -> scope.operand(value, factory));
    }

    private static class Json<T> extends AbstractJsonExpression<T> implements PgJsonExpression<T> {
        private Json(ExpressionScope scope, ProDsl portable, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
            this(scope, portable, PgSqlTypes.json(), source, expression);
        }

        private Json(ExpressionScope scope, ProDsl portable, NativeJsonType type, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
            super(scope, portable, type, source, expression);
        }

        @Override
        public PgJsonbExpression<T> toJsonb() {
            return new Jsonb<>(scope, portable, this, factory -> factory.anySQLFunction("CAST({0} AS JSONB)", args -> args.sqlFunc(func().apply(factory))));
        }
    }

    private static final class Jsonb<T> extends Json<T> implements PgJsonbExpression<T> {
        private Jsonb(ExpressionScope scope, ProDsl portable, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
            super(scope, portable, PgSqlTypes.jsonb(), source, expression);
        }

        @Override
        public PgJsonbExpression<T> toJsonb() {
            return this;
        }

        @Override
        public BooleanTypeExpression<Boolean> containsJson(JsonInput candidate) {
            return jsonComparison(candidate, "@>");
        }

        @Override
        public BooleanTypeExpression<Boolean> containedBy(JsonInput container) {
            return jsonComparison(container, "<@");
        }

        private BooleanTypeExpression<Boolean> jsonComparison(JsonInput value, String operator) {
            var parameter = AbstractJsonExpression.literal(scope, PgSqlTypes.jsonb(), value);
            return predicate(factory -> factory.anySQLFunction("({0} " + operator + " {1})", args ->
                    args.sqlFunc(func().apply(factory)).sqlFunc(scope.operand(parameter, factory))));
        }

        @Override
        public BooleanTypeExpression<Boolean> hasKey(String key) {
            String checked = checkedKey(key);
            // pgJDBC 用 ?? 表示原生 ? 运算符；保留 operator 节点以便数据库使用 JSONB 索引。
            return predicate(factory -> factory.anySQLFunction("({0} ?? CAST({1} AS TEXT))", args -> args.sqlFunc(func().apply(factory)).value(checked)));
        }

        @Override
        public BooleanTypeExpression<Boolean> hasAnyKey(String... keys) {
            return keys(keys, "??|");
        }

        @Override
        public BooleanTypeExpression<Boolean> hasAllKeys(String... keys) {
            return keys(keys, "??&");
        }

        private BooleanTypeExpression<Boolean> keys(String[] keys, String operator) {
            var checked = textValues(Arrays.asList(Objects.requireNonNull(keys, "keys").clone()));
            return predicate(factory -> factory.anySQLFunction("({0} " + operator + " {1})", args ->
                    args.sqlFunc(func().apply(factory)).sqlFunc(textArray(factory, checked))));
        }

        @Override
        public BooleanTypeExpression<Boolean> pathExists(PgJsonPath path) {
            Objects.requireNonNull(path, "path");
            return predicate(factory -> factory.anySQLFunction("({0} @?? CAST({1} AS JSONPATH))", args ->
                    args.sqlFunc(func().apply(factory)).value(path.expression())));
        }

        @Override
        public BooleanTypeExpression<Boolean> pathExists(PgJsonPath path, JsonInput variables, boolean silent) {
            Objects.requireNonNull(path, "path");
            var parameter = AbstractJsonExpression.literal(scope, PgSqlTypes.jsonb(), variables);
            return predicate(factory -> factory.anySQLFunction("jsonb_path_exists({0},CAST({1} AS JSONPATH),{2},{3})", args ->
                    args.sqlFunc(func().apply(factory)).value(path.expression()).sqlFunc(scope.operand(parameter, factory)).value(silent)));
        }

        @Override
        public PgJsonbExpression<T> setPath(List<String> path, JsonInput value, boolean createMissing) {
            var checked = textValues(path);
            var parameter = AbstractJsonExpression.literal(scope, PgSqlTypes.jsonb(), value);
            return new Jsonb<>(scope, portable, this, factory -> factory.anySQLFunction("jsonb_set({0},{1},{2},{3})", args ->
                    args.sqlFunc(func().apply(factory)).sqlFunc(textArray(factory, checked)).sqlFunc(scope.operand(parameter, factory)).value(createMissing)));
        }

        @Override
        public PgJsonbExpression<T> removeKey(String key) {
            String checked = checkedKey(key);
            return new Jsonb<>(scope, portable, this, factory -> factory.anySQLFunction("({0} - CAST({1} AS TEXT))", args -> args.sqlFunc(func().apply(factory)).value(checked)));
        }

        @Override
        public PgJsonbExpression<T> removePath(List<String> path) {
            var checked = textValues(path);
            return new Jsonb<>(scope, portable, this, factory -> factory.anySQLFunction("({0} #- {1})", args -> args.sqlFunc(func().apply(factory)).sqlFunc(textArray(factory, checked))));
        }

        private BooleanTypeExpression<Boolean> predicate(Function<SQLFunc, SQLFunction> expression) {
            return new BooleanTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.function(expression.apply(factory), AggregationType.UNKNOWN, true, getTable()), Boolean.class);
        }

        private static String checkedKey(String key) {
            return UnicodeStrings.requirePortable(Objects.requireNonNull(key, "key"), "JSON key");
        }

        private static List<String> textValues(List<String> values) {
            Objects.requireNonNull(values, "path or keys");
            return values.stream().map(Jsonb::checkedKey).toList();
        }

        private static SQLFunction textArray(SQLFunc factory, List<String> values) {
            String elements = IntStream.range(0, values.size()).mapToObj(i -> "CAST({" + i + "} AS TEXT)").collect(Collectors.joining(","));
            return factory.anySQLFunction("ARRAY[" + elements + "]::TEXT[]", args -> values.forEach(args::value));
        }
    }
}
