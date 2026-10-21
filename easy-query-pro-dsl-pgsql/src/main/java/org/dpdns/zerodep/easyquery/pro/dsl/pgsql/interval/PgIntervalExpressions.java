package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

import java.util.Locale;
import java.util.function.Function;
import com.easy.query.core.metadata.ColumnMetadata;
import java.lang.reflect.Field;
import com.easy.query.core.util.EasyStringUtil;

/**
 * Internal interval operations using the existing function, ownership and result-mapping chain.
 */
public final class PgIntervalExpressions {
    private PgIntervalExpressions() {
    }

    public static <T> PgIntervalExpression<T> view(ExpressionScope scope, PropTypeColumn<T> source) {
        return new Expression<>(scope, source, factory -> scope.operand(source, factory));
    }

    static void requireView(ExpressionScope scope, PropTypeColumn<?> source) {
        scope.requireSource(source);
        if (!(source instanceof DSLSQLFunctionAvailable) && source.getValue() != null && source.getTable() != null) {
            ColumnMetadata column = source.getTable().getEntityMetadata().getColumnNotNull(source.getValue());
            Field field = EasyClassUtil.getAllFieldsMap(source.getTable().getEntityClass()).get(column.getFieldName());
            Column annotation = field == null ? null : field.getAnnotation(Column.class);
            if (column.getColumnValueSQLConverter() == null && annotation != null && !EasyStringUtil.isBlank(annotation.dbType())
                    && !annotation.dbType().trim().toUpperCase(Locale.ROOT).matches("INTERVAL(?:\\s.*|\\(.*)?")) {
                throw new IllegalArgumentException("Column " + column.getPropertyName() + " does not declare INTERVAL; use an explicit CAST");
            }
        }
    }

    public static PgIntervalExpression<PgInterval> value(ExpressionScope scope, PgInterval value) {
        AnyTypeExpressionImpl<PgInterval> source = new AnyTypeExpressionImpl<PgInterval>(scope.context(), scope.anchor(), null,
                factory -> scope.function(factory.anySQLFunction("CAST({0} AS INTERVAL)", args -> args.value(value)), AggregationType.UNKNOWN, false), PgInterval.class);
        return new Expression<>(scope, source, factory -> scope.operand(source, factory));
    }

    public static PgIntervalExpression<PgInterval> cast(ExpressionScope scope, PropTypeColumn<?> source) {
        scope.requireSource(source);
        AnyTypeExpressionImpl<PgInterval> cast = new AnyTypeExpressionImpl<PgInterval>(scope.context(), source.getTable(), null,
                factory -> scope.scalar(factory, source, value -> factory.anySQLFunction("CAST({0} AS INTERVAL)", args -> args.sqlFunc(value))), PgInterval.class);
        return new Expression<>(scope, cast, factory -> scope.operand(cast, factory));
    }

    private static final class Expression<T> extends AbstractPgIntervalExpression<T, Expression<T>> {
        private Expression(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> function) {
            super(scope, source, function);
        }

        @Override
        protected Expression<T> recreate(PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> expression) {
            return new Expression<>(scope, source, expression);
        }
    }
}
