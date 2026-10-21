package org.dpdns.zerodep.easyquery.pro.dsl.hana.lob;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.expression.builder.AsSelector;
import com.easy.query.core.expression.builder.Selector;
import com.easy.query.core.expression.builder.GroupSelector;
import com.easy.query.core.expression.builder.OrderSelector;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.def.enums.OrderByModeEnum;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.SQLFunctionExpressionUtil;
import com.easy.query.core.proxy.extension.functions.type.*;
import com.easy.query.core.proxy.extension.functions.type.impl.*;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.MappedValueExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CastTarget;
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaLobSqlSupport;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import com.easy.query.core.metadata.ColumnMetadata;
import java.lang.reflect.Field;
import com.easy.query.core.util.EasyStringUtil;

public final class HanaLobExpressions {
    private HanaLobExpressions() {
    }

    public static <T> HanaTextLobExpression<T> view(ExpressionScope scope, PropTypeColumn<T> source, HanaTypes.TextLob type) {
        requireView(scope, source, type);
        return new Text<>(scope, source, type, factory -> scope.operand(source, factory));
    }

    public static <T> HanaBinaryLobExpression<T> view(ExpressionScope scope, PropTypeColumn<T> source, HanaTypes.BinaryLob type) {
        requireView(scope, source, type);
        return new Binary<>(scope, source, factory -> scope.operand(source, factory));
    }

    public static <J> PropTypeColumn<J> value(ExpressionScope scope, J value, CastTarget<J> type) {
        J checked = type.prepareValue(value);
        String template = type.castTemplate(scope.dialect());
        return new AnyTypeExpressionImpl<J>(scope.context(), scope.anchor(), null,
                factory -> scope.function(factory.anySQLFunction(template, args -> args.value(checked)), AggregationType.UNKNOWN, false), type.javaType());
    }

    public static <J> PropTypeColumn<J> cast(ExpressionScope scope, PropTypeColumn<?> source, CastTarget<J> type) {
        scope.requireSource(source);
        String template = type.castTemplate(scope.dialect());
        return new AnyTypeExpressionImpl<J>(scope.context(), source.getTable(), null,
                factory -> scope.scalar(factory, source, operand -> factory.anySQLFunction(template, args -> args.sqlFunc(operand))), type.javaType());
    }

    private static void requireView(ExpressionScope scope, PropTypeColumn<?> source, HanaTypes.Lob<?> type) {
        Objects.requireNonNull(type, "type");
        scope.requireSource(source);
        if (scope.dialect() != SqlDialect.HANA) throw new IllegalArgumentException("HANA LOB expressions require HANA");
        if (source instanceof HanaLobExpression<?> && ((HanaLobExpression<?>) source).sqlType() != type)
            throw new IllegalArgumentException("Use an explicit CAST between LOB storage types");
        if (source instanceof DSLSQLFunctionAvailable || source.getTable() == null || source.getValue() == null) return;
        ColumnMetadata column = source.getTable().getEntityMetadata().getColumnNotNull(source.getValue());
        if (column.getColumnValueSQLConverter() != null) return;
        Field field = EasyClassUtil.getAllFieldsMap(source.getTable().getEntityClass()).get(column.getFieldName());
        Column annotation = field == null ? null : field.getAnnotation(Column.class);
        if (annotation != null && !EasyStringUtil.isBlank(annotation.dbType())) {
            String declared = annotation.dbType().trim().toUpperCase(Locale.ROOT).split("[\\s(]", 2)[0];
            if (!declared.equals(type.sqlName()))
                throw new IllegalArgumentException("Column " + column.getPropertyName() + " declares " + declared + "; use an explicit " + type.sqlName() + " CAST");
        }
    }

    private abstract static class Lob<T> extends MappedValueExpression<T> implements HanaLobExpression<T> {
        private Lob(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
            super(scope, source, expression);
        }

        @Override
        public final void accept(Selector target) {
            SQLFunctionExpressionUtil.accept(target, getTable(), func());
        }

        @Override
        public final void accept(AsSelector target) {
            SQLFunctionExpressionUtil.accept(target, getTable(), func());
        }

        @Override
        public final void accept(GroupSelector target) {
            throw HanaLobSqlSupport.unsupportedClause("GROUP BY");
        }

        @Override
        public final void accept(OrderSelector target) {
            throw HanaLobSqlSupport.unsupportedClause("ORDER BY");
        }

        @Override
        public final void asc(boolean condition, OrderByModeEnum nulls) {
            if (condition) throw HanaLobSqlSupport.unsupportedClause("ORDER BY");
        }

        @Override
        public final void desc(boolean condition, OrderByModeEnum nulls) {
            if (condition) throw HanaLobSqlSupport.unsupportedClause("ORDER BY");
        }

        @Override
        public final Function<SQLFunc, SQLFunction> func() {
            Function<SQLFunc, SQLFunction> function = super.func();
            return factory -> {
                SQLFunction value = function.apply(factory);
                return scope.operation(HanaLobSqlSupport.lobResult(value), AggregationType.UNKNOWN, value);
            };
        }

        @Override
        public final BooleanTypeExpression<Boolean> isSqlNull() {
            return new BooleanTypeExpressionImpl<>(scope.context(), scope.anchor(), null, factory -> scope.scalar(factory, this, value ->
                    factory.anySQLFunction("(CASE WHEN {0} IS NULL THEN TRUE ELSE FALSE END)", args -> args.sqlFunc(value))), Boolean.class);
        }

        protected final NumberTypeExpression<Long> lobLength() {
            return new NumberTypeExpressionImpl<>(scope.context(), scope.anchor(), null, factory -> scope.scalar(factory, this, value ->
                    factory.anySQLFunction("LENGTH({0})", args -> args.sqlFunc(value))), Long.class);
        }

        protected final SQLFunction slice(SQLFunc factory, int start, int length) {
            return scope.scalar(factory, this, value -> factory.anySQLFunction("SUBSTR({0},{1},{2})", args -> args.sqlFunc(value).value(start).value(length)));
        }

        protected final void requireSlice(int start, int length) {
            if (start < 1 || length < 0 || length > 5000)
                throw new IllegalArgumentException("LOB slices require a positive one-based start and 0 <= length <= 5000");
        }
    }

    private static final class Text<T> extends Lob<T> implements HanaTextLobExpression<T> {
        private final HanaTypes.TextLob type;

        private Text(ExpressionScope scope, PropTypeColumn<T> source, HanaTypes.TextLob type, Function<SQLFunc, SQLFunction> expression) {
            super(scope, source, expression);
            this.type = type;
        }

        @Override
        public HanaTypes.TextLob sqlType() {
            return type;
        }

        @Override
        public NumberTypeExpression<Long> utf16Length() {
            return lobLength();
        }

        @Override
        public StringTypeExpression<String> sliceUtf16(int start, int length) {
            requireSlice(start, length);
            return new StringTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> slice(factory, start, length), String.class);
        }

        @Override
        public StringTypeExpression<String> asText() {
            return new StringTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> scope.scalar(factory, this, value -> factory.cast(value, String.class)), String.class);
        }
    }

    private static final class Binary<T> extends Lob<T> implements HanaBinaryLobExpression<T> {
        private Binary(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
            super(scope, source, expression);
        }

        @Override
        public HanaTypes.BinaryLob sqlType() {
            return HanaTypes.blob();
        }

        @Override
        public NumberTypeExpression<Long> byteLength() {
            return lobLength();
        }

        @Override
        public AnyTypeExpression<byte[]> sliceBytes(int start, int length) {
            requireSlice(start, length);
            return new AnyTypeExpressionImpl<>(scope.context(), scope.anchor(), null,
                    factory -> slice(factory, start, length), byte[].class);
        }
    }
}
