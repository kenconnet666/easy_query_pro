package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.basic.extension.conversion.ColumnReaderImpl;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.SQLSelectAsExpression;
import com.easy.query.core.proxy.extension.functions.type.IgnoreValueConverterTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.util.EasyClassUtil;

import java.util.Objects;
import java.util.function.Function;

/**
 * Whole-value operations preserve the source column's reader and Java mapping in scalar and Draft projections.
 */
public abstract class MappedValueExpression<T> extends AnyTypeExpressionImpl<T> {
    protected final ExpressionScope scope;
    private final ColumnMetadata mapping;

    protected MappedValueExpression(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> function) {
        this(scope, source, function, mapping(source));
    }

    private MappedValueExpression(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> function, ColumnMetadata mapping) {
        super(scope.context(), source.getTable(), mapping == null ? null : source.getValue(),
                factory -> scope.function(function.apply(factory), AggregationType.UNKNOWN, true, source.getTable()),
                EasyClassUtil.getObjectTypeWhenPrimitive(Objects.requireNonNull(source.getPropertyType(), "source Java type")));
        // Keep APT's Object.class carrier: selectColumn must not reinterpret a converted record/List as a new entity.
        this.scope = scope;
        this.mapping = mapping;
    }

    private static ColumnMetadata mapping(PropTypeColumn<?> source) {
        if (source instanceof IgnoreValueConverterTypeExpression || source.getTable() == null || source.getValue() == null)
            return null;
        return source.getTable().getEntityMetadata().getColumnOrNull(source.getValue());
    }

    @Override
    public SQLSelectAsExpression as(String alias) {
        if (mapping != null) {
            // Draft only picks column metadata for SQLColumn. Reuse its public reader SPI for computed expressions.
            String key = alias == null ? mapping.getPropertyName() : alias;
            scope.context().getExpressionContext().getResultValueConverterMap(true).put(key,
                    new ColumnReaderImpl(getTable().getEntityMetadata(), mapping, null));
        }
        return super.as(alias);
    }
}
