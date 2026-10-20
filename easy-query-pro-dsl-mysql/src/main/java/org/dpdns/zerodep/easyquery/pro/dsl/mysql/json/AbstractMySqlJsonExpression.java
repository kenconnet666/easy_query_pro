package org.dpdns.zerodep.easyquery.pro.dsl.mysql.json;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.AbstractJsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.BiFunction;

/**
 * Whole-document operations preserve the source reader and the concrete extension type.
 */
public abstract class AbstractMySqlJsonExpression<T, S extends MySqlJsonExpression<T>> extends AbstractJsonExpression<T> implements MySqlJsonExpression<T> {
    protected AbstractMySqlJsonExpression(ExpressionScope scope, ProDsl portable, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
        super(scope, portable, MySqlTypes.json(), source, expression);
        requireView(scope, source, MySqlTypes.json());
    }

    /**
     * Pass the previous expression and supplied SQL callback unchanged; using the original entity column would lose the chain.
     */
    protected abstract S recreate(PropTypeColumn<T> mappingSource, Function<SQLFunc, SQLFunction> expression);

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
    public S setPath(JsonPath path, JsonInput value) {
        return setPaths(new MySqlJsonChange(path, value));
    }

    @Override
    public S setPaths(MySqlJsonChange... changes) {
        var checked = List.of(Objects.requireNonNull(changes, "changes"));
        if (checked.isEmpty()) throw new IllegalArgumentException("JSON_SET requires at least one change");
        var values = checked.stream().map(change -> literal(scope, MySqlTypes.json(), change.value())).toList();
        var template = new StringBuilder("JSON_SET({0}");
        for (int i = 0; i < checked.size(); i++) {
            template.append(",CONVERT({").append(2 * i + 1).append("} USING utf8mb4),{").append(2 * i + 2).append('}');
        }
        template.append(')');
        return recreate(this, factory -> scope.scalar(factory, this, document -> factory.anySQLFunction(template.toString(), args -> {
            args.sqlFunc(document);
            for (int i = 0; i < checked.size(); i++) {
                args.value(checked.get(i).path().toSqlJsonPath()).sqlFunc(scope.operand(values.get(i), factory));
            }
        })));
    }

    @Override
    public S removePath(JsonPath path) {
        return removePaths(path);
    }

    @Override
    public S removePaths(JsonPath... paths) {
        var checked = checkedPaths(paths);
        if (checked.stream().anyMatch(path -> path.segments().isEmpty())) {
            throw new IllegalArgumentException("JSON_REMOVE cannot remove the document root");
        }
        String template = "JSON_REMOVE({0}" + pathArguments(checked.size()) + ")";
        return recreate(this, factory -> scope.scalar(factory, this, document -> factory.anySQLFunction(template, args -> {
            args.sqlFunc(document);
            checked.forEach(path -> args.value(path.toSqlJsonPath()));
        })));
    }

    protected BooleanTypeExpression<Boolean> predicate(BiFunction<SQLFunc, SQLFunction, SQLFunction> expression) {
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
