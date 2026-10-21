package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.DateTimeTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.AnyTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.DateTimeTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.AbstractJsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
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
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.window.PgWindowSpec;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTextSearchType;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.MappedValueExpression;

import java.util.Arrays;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;

public class PgSqlDsl extends ProDsl {

    protected PgSqlDsl(ProxyEntity<?, ?> entity) {
        this(new ExpressionScope(entity));
    }

    protected PgSqlDsl(ExpressionScope scope) {
        super(scope);
        PgSqlTypes.json().requireDialect(scope.dialect());
    }

    public static PgSqlDsl of(ProxyEntity<?, ?> entity) {
        return new PgSqlDsl(entity);
    }

    public PgWindowSpec<?> nativeWindow() {
        return PgWindowSpec.create(scope);
    }

    /**
     * 绑定原生编码值，保留数据库对输入语法的规范化和错误行为。
     * 原文分词及语言词典处理仍由 PostgreSQL 的文本分析函数完成。
     */
    public <T> AnyTypeExpression<T> value(T value, PgTextSearchType<T> type) {
        return super.value(value, type, (bound, source, expression) -> new MappedValueExpression<T>(bound, source, expression) {
        });
    }

    /**
     * 执行真实 PostgreSQL CAST，语法错误、规范化和范围限制仍由数据库决定。
     */
    public <T> AnyTypeExpression<T> nativeCast(PropTypeColumn<?> source, PgTextSearchType<T> type) {
        return super.nativeCast(source, type, (bound, mapping, expression) -> new MappedValueExpression<T>(bound, mapping, expression) {
        });
    }

    /**
     * 将无时区 timestamp 放入从 origin 对齐的固定时长桶，返回桶起点，SQL NULL 来源仍返回 NULL。
     *
     * <p>Duration 不表示自然月或带时区自然日；月份/年等截断继续使用共同 truncateDateTime。
     * stride 和 origin 必须精确到微秒，避免参数在绑定阶段舍入后改变桶边界。
     * interval 绑定沿用 PG 原生类型处理器，执行前需完成 configureTypeHandlers。
     */
    public DateTimeTypeExpression<LocalDateTime> dateBin(PropTypeColumn<LocalDateTime> source,
                                                         Duration stride, LocalDateTime origin) {
        scope.requireSource(source);
        Objects.requireNonNull(origin, "origin");
        if (origin.equals(LocalDateTime.MIN) || origin.equals(LocalDateTime.MAX) || origin.getNano() % 1_000 != 0)
            throw new IllegalArgumentException("date_bin origin must be finite and exact to microseconds");
        PgInterval.Finite interval = PgInterval.of(0, 0, fixedMicroseconds(stride));
        return new DateTimeTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                factory -> scope.scalar(factory, source, value -> factory.anySQLFunction(
                        "date_bin(CAST({0} AS INTERVAL),CAST({1} AS TIMESTAMP WITHOUT TIME ZONE),CAST({2} AS TIMESTAMP WITHOUT TIME ZONE))",
                        args -> args.value(interval).sqlFunc(value).value(origin))), LocalDateTime.class);
    }

    private static long fixedMicroseconds(Duration stride) {
        Objects.requireNonNull(stride, "stride");
        if (stride.isZero() || stride.isNegative() || stride.getNano() % 1_000 != 0)
            throw new IllegalArgumentException("date_bin stride must be positive and exact to microseconds");
        try {
            // 分别计算秒和小数部分，避免 toNanos 对本可用的长时长提前溢出。
            return Math.addExact(Math.multiplyExact(stride.getSeconds(), 1_000_000L), stride.getNano() / 1_000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("date_bin stride exceeds signed microseconds", overflow);
        }
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
        return new Json<>(scope, this, source, factory -> scope.operand(source, factory));
    }

    public <T> PgJsonbExpression<T> view(PropTypeColumn<T> source, PgSqlTypes.Jsonb type) {
        AbstractJsonExpression.requireView(scope, source, Objects.requireNonNull(type, "type"));
        return new Jsonb<>(scope, this, source, factory -> scope.operand(source, factory));
    }

    public PgJsonExpression<String> nativeCast(PropTypeColumn<?> source, PgSqlTypes.Json type) {
        PropTypeColumn<String> converted = AbstractJsonExpression.cast(scope, Objects.requireNonNull(type, "type"), source);
        return new Json<>(scope, this, converted, factory -> scope.operand(converted, factory));
    }

    public PgJsonbExpression<String> nativeCast(PropTypeColumn<?> source, PgSqlTypes.Jsonb type) {
        PropTypeColumn<String> converted = AbstractJsonExpression.cast(scope, Objects.requireNonNull(type, "type"), source);
        return new Jsonb<>(scope, this, converted, factory -> scope.operand(converted, factory));
    }

    public PgJsonExpression<String> value(JsonInput input, PgSqlTypes.Json type) {
        PropTypeColumn<String> value = AbstractJsonExpression.literal(scope, Objects.requireNonNull(type, "type"), input);
        return new Json<>(scope, this, value, factory -> scope.operand(value, factory));
    }

    public PgJsonbExpression<String> value(JsonInput input, PgSqlTypes.Jsonb type) {
        PropTypeColumn<String> value = AbstractJsonExpression.literal(scope, Objects.requireNonNull(type, "type"), input);
        return new Jsonb<>(scope, this, value, factory -> scope.operand(value, factory));
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
            return new Jsonb<>(scope, portable, this, factory -> scope.scalar(factory, this, document -> factory.anySQLFunction("CAST({0} AS JSONB)", args -> args.sqlFunc(document))));
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
            PropTypeColumn<String> parameter = AbstractJsonExpression.literal(scope, PgSqlTypes.jsonb(), value);
            return predicate((factory, document) -> factory.anySQLFunction("({0} " + operator + " {1})", args ->
                    args.sqlFunc(document).sqlFunc(scope.operand(parameter, factory))));
        }

        @Override
        public BooleanTypeExpression<Boolean> hasKey(String key) {
            String checked = checkedKey(key);
            // pgJDBC 用 ?? 表示原生 ? 运算符；保留 operator 节点以便数据库使用 JSONB 索引。
            return predicate((factory, document) -> factory.anySQLFunction("({0} ?? CAST({1} AS TEXT))", args -> args.sqlFunc(document).value(checked)));
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
            List<String> checked = textValues(Arrays.asList(Objects.requireNonNull(keys, "keys").clone()));
            return predicate((factory, document) -> factory.anySQLFunction("({0} " + operator + " {1})", args ->
                    args.sqlFunc(document).sqlFunc(textArray(factory, checked))));
        }

        @Override
        public BooleanTypeExpression<Boolean> pathExists(PgJsonPath path) {
            Objects.requireNonNull(path, "path");
            return predicate((factory, document) -> factory.anySQLFunction("({0} @?? CAST({1} AS JSONPATH))", args ->
                    args.sqlFunc(document).value(path.expression())));
        }

        @Override
        public BooleanTypeExpression<Boolean> pathExists(PgJsonPath path, JsonInput variables, boolean silent) {
            Objects.requireNonNull(path, "path");
            PropTypeColumn<String> parameter = AbstractJsonExpression.literal(scope, PgSqlTypes.jsonb(), variables);
            return predicate((factory, document) -> factory.anySQLFunction("jsonb_path_exists({0},CAST({1} AS JSONPATH),{2},{3})", args ->
                    args.sqlFunc(document).value(path.expression()).sqlFunc(scope.operand(parameter, factory)).value(silent)));
        }

        @Override
        public PgJsonbExpression<T> setPath(List<String> path, JsonInput value, boolean createMissing) {
            List<String> checked = textValues(path);
            PropTypeColumn<String> parameter = AbstractJsonExpression.literal(scope, PgSqlTypes.jsonb(), value);
            return new Jsonb<>(scope, portable, this, factory -> scope.scalar(factory, this, document -> factory.anySQLFunction("jsonb_set({0},{1},{2},{3})", args ->
                    args.sqlFunc(document).sqlFunc(textArray(factory, checked)).sqlFunc(scope.operand(parameter, factory)).value(createMissing))));
        }

        @Override
        public PgJsonbExpression<T> removeKey(String key) {
            String checked = checkedKey(key);
            return new Jsonb<>(scope, portable, this, factory -> scope.scalar(factory, this, document -> factory.anySQLFunction("({0} - CAST({1} AS TEXT))", args -> args.sqlFunc(document).value(checked))));
        }

        @Override
        public PgJsonbExpression<T> removePath(List<String> path) {
            List<String> checked = textValues(path);
            return new Jsonb<>(scope, portable, this, factory -> scope.scalar(factory, this, document -> factory.anySQLFunction("({0} #- {1})", args -> args.sqlFunc(document).sqlFunc(textArray(factory, checked)))));
        }

        private BooleanTypeExpression<Boolean> predicate(BiFunction<SQLFunc, SQLFunction, SQLFunction> expression) {
            return new BooleanTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.scalar(factory, this, document -> expression.apply(factory, document)), Boolean.class);
        }

        private static String checkedKey(String key) {
            return UnicodeStrings.requirePortable(Objects.requireNonNull(key, "key"), "JSON key");
        }

        private static List<String> textValues(List<String> values) {
            Objects.requireNonNull(values, "path or keys");
            return values.stream().map(Jsonb::checkedKey).collect(ImmutableCollections.toList());
        }

        private static SQLFunction textArray(SQLFunc factory, List<String> values) {
            String elements = IntStream.range(0, values.size()).mapToObj(i -> "CAST({" + i + "} AS TEXT)").collect(Collectors.joining(","));
            return factory.anySQLFunction("ARRAY[" + elements + "]::TEXT[]", args -> values.forEach(args::value));
        }
    }
}
