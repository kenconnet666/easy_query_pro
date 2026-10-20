package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.basic.extension.conversion.ColumnReader;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.SQLSelectAsExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.util.EasyClassUtil;

import java.util.Objects;
import java.util.function.Function;

/**
 * Whole-value operations preserve the source column's reader and Java mapping in scalar and Draft projections.
 */
public abstract class MappedValueExpression<T> extends AnyTypeExpressionImpl<T> {
    protected final ExpressionScope scope;
    private final ColumnReader reader;

    protected MappedValueExpression(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> function) {
        this(scope, source, function, ColumnReaders.source(source));
    }

    private MappedValueExpression(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> function, ColumnReader reader) {
        super(scope.context(), source.getTable(), reader == null ? null : source.getValue(),
                factory -> {
                    var value = function.apply(factory);
                    return scope.function(value, AggregationType.UNKNOWN, !ExpressionScope.isRowLocal(value), source.getTable());
                },
                EasyClassUtil.getObjectTypeWhenPrimitive(Objects.requireNonNull(source.getPropertyType(), "source Java type")));
        // Keep APT's Object.class carrier: selectColumn must not reinterpret a converted record/List as a new entity.
        this.scope = scope;
        this.reader = reader;
    }

    final ColumnReader columnReader() {
        return reader;
    }

    @Override
    public SQLSelectAsExpression as(String alias) {
        if (reader != null) {
            // Draft only picks column metadata for SQLColumn. Reuse its public reader SPI for computed expressions.
            String key = alias == null ? getValue() : alias;
            ColumnReaders.setAutomatic(scope.context().getEntityExpressionBuilder(), key, reader);
        }
        return super.as(alias);
    }
}
