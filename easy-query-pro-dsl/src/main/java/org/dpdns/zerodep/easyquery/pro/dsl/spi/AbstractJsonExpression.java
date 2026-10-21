package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.NativeJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.json.*;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;

import java.sql.JDBCType;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;
import com.easy.query.core.metadata.ColumnMetadata;
import java.lang.reflect.Field;
import com.easy.query.core.util.EasyStringUtil;

/**
 * Reuses the existing property mapping and the portable reader without exposing text methods on JSON.
 */
public abstract class AbstractJsonExpression<T> extends MappedValueExpression<T> implements JsonExpression<T> {
    private static final Set<String> KNOWN_TYPES = ImmutableCollections.setOf("JSON", "JSONB", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "BYTEA", "UUID", "INT", "INT2", "INT4", "INT8", "BOOL", "DATETIME", "TIMESTAMPTZ");
    protected final ProDsl portable;
    private final NativeJsonType type;

    protected AbstractJsonExpression(ExpressionScope scope, ProDsl portable, NativeJsonType type, PropTypeColumn<T> source,
                                     Function<SQLFunc, SQLFunction> expression) {
        super(scope, source, expression);
        this.portable = portable;
        this.type = type;
    }

    @Override
    public NativeJsonType sqlType() {
        return type;
    }


    @Override
    public JsonFragmentExpression at(JsonPath path) {
        return portable.jsonAt(this, path);
    }

    @Override
    public StringTypeExpression<String> textAt(JsonPath path) {
        return portable.jsonTextAt(this, path);
    }

    @Override
    public BooleanTypeExpression<Boolean> booleanAt(JsonPath path) {
        return portable.jsonBooleanAt(this, path);
    }

    @Override
    public BooleanTypeExpression<Boolean> existsAt(JsonPath path) {
        return portable.jsonExists(this, path);
    }

    @Override
    public StringTypeExpression<String> asText() {
        return new StringTypeExpressionImpl<>(scope.context(), scope.anchor(), null, factory ->
                scope.scalar(factory, this, value -> scope.runtime().getService(JsonPathAccess.class).serializedText(factory, value)), String.class);
    }

    public static void requireView(ExpressionScope scope, PropTypeColumn<?> source, NativeJsonType target) {
        scope.requireSource(source);
        target.requireDialect(scope.dialect());
        if (source instanceof JsonExpression<?> && !((JsonExpression<?>) source).sqlType().equals(target)) {
            JsonExpression<?> known = (JsonExpression<?>) source;
            throw new IllegalArgumentException("Use an explicit CAST from " + known.sqlType().sqlName() + " to " + target.sqlName());
        }
        if (source instanceof JsonFragmentExpression && target.dialect() == SqlDialect.POSTGRESQL && target.sqlName().equals("JSONB")) {
            throw new IllegalArgumentException("Portable JSON fragments require an explicit JSONB CAST");
        }
        if (source instanceof DSLSQLFunctionAvailable || source.getTable() == null || source.getValue() == null) return;
        ColumnMetadata column = source.getTable().getEntityMetadata().getColumnNotNull(source.getValue());
        if (column.getColumnValueSQLConverter() != null && !(column.getColumnValueSQLConverter() instanceof NativeJsonColumnConverter))
            return;
        Field field = EasyClassUtil.getAllFieldsMap(source.getTable().getEntityClass()).get(column.getFieldName());
        Column annotation = field == null ? null : field.getAnnotation(Column.class);
        if (annotation == null || EasyStringUtil.isBlank(annotation.dbType())) return;
        String declared = annotation.dbType().trim().toUpperCase(Locale.ROOT).split("[\\s(]", 2)[0];
        // 已知内置声明才做冲突判断；未知 domain 或自定义类型仍是调用者的类型声明。
        boolean known = KNOWN_TYPES.contains(declared);
        try {
            JDBCType.valueOf(declared);
            known = true;
        } catch (IllegalArgumentException ignored) {
        }
        if (known && !target.matchesDeclaration(annotation.dbType())) {
            throw new IllegalArgumentException("Column " + column.getPropertyName() + " declares " + declared + "; use an explicit " + target.sqlName() + " CAST");
        }
    }

    public static PropTypeColumn<String> literal(ExpressionScope scope, NativeJsonType target, JsonInput input) {
        Objects.requireNonNull(input, "input");
        String value = target.prepareValue(input.json());
        String template = target.castTemplate(scope.dialect());
        return new AnyTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                factory -> scope.function(factory.anySQLFunction(template, args -> args.value(value)), AggregationType.UNKNOWN, false), String.class);
    }

    public static PropTypeColumn<String> cast(ExpressionScope scope, NativeJsonType target, PropTypeColumn<?> source) {
        scope.requireSource(source);
        String template = target.castTemplate(scope.dialect());
        return new AnyTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                factory -> scope.scalar(factory, source, value -> factory.anySQLFunction(template, args -> args.sqlFunc(source.getTable(), value))), String.class);
    }
}
