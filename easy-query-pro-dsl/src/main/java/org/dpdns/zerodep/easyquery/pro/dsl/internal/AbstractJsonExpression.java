package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.IgnoreValueConverterTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.json.*;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;

import java.sql.JDBCType;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Reuses the existing property mapping and the portable reader without exposing text methods on JSON.
 */
public abstract class AbstractJsonExpression<T> extends AnyTypeExpressionImpl<T> implements JsonExpression<T> {
    private static final Set<String> KNOWN_TYPES = Set.of("JSON", "JSONB", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "BYTEA", "UUID", "INT", "INT2", "INT4", "INT8", "BOOL", "DATETIME", "TIMESTAMPTZ");
    protected final ExpressionScope scope;
    protected final ProDsl portable;
    private final NativeJsonType type;

    protected AbstractJsonExpression(ExpressionScope scope, ProDsl portable, NativeJsonType type, PropTypeColumn<T> source,
                                     Function<SQLFunc, SQLFunction> expression) {
        super(scope.context(), source.getTable(), source instanceof IgnoreValueConverterTypeExpression ? null : source.getValue(),
                factory -> scope.function(expression.apply(factory), AggregationType.UNKNOWN, true, source.getTable()),
                EasyClassUtil.getObjectTypeWhenPrimitive(Objects.requireNonNull(source.getPropertyType(), "source Java type")));
        this.scope = scope;
        this.portable = portable;
        this.type = type;
    }

    @Override
    public final NativeJsonType sqlType() {
        return type;
    }

    @Override
    public final JsonFragmentExpression at(JsonPath path) {
        return portable.jsonAt(this, path);
    }

    @Override
    public final StringTypeExpression<String> textAt(JsonPath path) {
        return portable.jsonTextAt(this, path);
    }

    @Override
    public final BooleanTypeExpression<Boolean> booleanAt(JsonPath path) {
        return portable.jsonBooleanAt(this, path);
    }

    @Override
    public final BooleanTypeExpression<Boolean> existsAt(JsonPath path) {
        return portable.jsonExists(this, path);
    }

    @Override
    public final StringTypeExpression<String> asText() {
        return new StringTypeExpressionImpl<>(scope.context(), scope.anchor(), null, factory ->
                scope.function(scope.runtime().getService(JsonPathAccess.class).serializedText(factory, func().apply(factory)),
                        AggregationType.UNKNOWN, true, getTable()), String.class);
    }

    public static void requireView(ExpressionScope scope, PropTypeColumn<?> source, NativeJsonType target) {
        scope.requireSource(source);
        target.requireDialect(scope.dialect());
        if (source instanceof JsonExpression<?> known && known.sqlType() != target) {
            throw new IllegalArgumentException("Use an explicit CAST from " + known.sqlType().sqlName() + " to " + target.sqlName());
        }
        if (source instanceof JsonFragmentExpression && target.dialect() == SqlDialect.POSTGRESQL && target.sqlName().equals("JSONB")) {
            throw new IllegalArgumentException("Portable JSON fragments require an explicit JSONB CAST");
        }
        if (source instanceof DSLSQLFunctionAvailable || source.getTable() == null || source.getValue() == null) return;
        var column = source.getTable().getEntityMetadata().getColumnNotNull(source.getValue());
        if (column.getColumnValueSQLConverter() != null && !(column.getColumnValueSQLConverter() instanceof NativeJsonColumnConverter))
            return;
        var field = EasyClassUtil.getAllFieldsMap(source.getTable().getEntityClass()).get(column.getFieldName());
        var annotation = field == null ? null : field.getAnnotation(Column.class);
        if (annotation == null || annotation.dbType().isBlank()) return;
        String declared = annotation.dbType().trim().toUpperCase(Locale.ROOT).split("[\\s(]", 2)[0];
        // 已知内置声明才做冲突判断；未知 domain 或自定义类型仍是调用者的类型声明。
        boolean known = KNOWN_TYPES.contains(declared);
        try {
            JDBCType.valueOf(declared);
            known = true;
        } catch (IllegalArgumentException ignored) {
        }
        if (known && !declared.equals(target.sqlName())) {
            throw new IllegalArgumentException("Column " + column.getPropertyName() + " declares " + declared + "; use an explicit " + target.sqlName() + " CAST");
        }
    }

    public static PropTypeColumn<String> literal(ExpressionScope scope, NativeJsonType target, JsonInput input) {
        Objects.requireNonNull(input, "input");
        String value = target.prepareValue(input.json());
        String template = target.castTemplate(scope.dialect());
        return new AnyTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                factory -> factory.anySQLFunction(template, args -> args.value(value)), String.class);
    }

    public static PropTypeColumn<String> cast(ExpressionScope scope, NativeJsonType target, PropTypeColumn<?> source) {
        scope.requireSource(source);
        String template = target.castTemplate(scope.dialect());
        return new AnyTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                factory -> scope.function(factory.anySQLFunction(template, args -> args.sqlFunc(source.getTable(), scope.operand(source, factory))),
                        AggregationType.UNKNOWN, true, source.getTable()), String.class);
    }
}
