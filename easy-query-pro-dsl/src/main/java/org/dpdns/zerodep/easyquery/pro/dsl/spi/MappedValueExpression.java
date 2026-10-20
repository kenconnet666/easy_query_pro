package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.basic.extension.conversion.ColumnReader;
import com.easy.query.core.basic.jdbc.executor.ResultColumnMetadata;
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
                    return scope.projection(value, source.getTable());
                },
                reader != null ? Object.class : EasyClassUtil.getObjectTypeWhenPrimitive(Objects.requireNonNull(source.getPropertyType(), "source Java type")));
        // A derived Draft can expose a concrete record/List type. Keep reader-backed results on the scalar path.
        this.scope = scope;
        this.reader = reader;
    }

    final ColumnReader columnReader() {
        if (reader == null) return null;
        var expression = func().apply(scope.runtime().fx());
        return ExpressionScope.preservesRows(expression) && !ExpressionScope.isRowLocal(expression) ? ColumnReaders.nullable(reader) : reader;
    }

    @Override
    public ResultColumnMetadata getTableResultColumnMetadataOrNull(int index) {
        var resultReader = columnReader();
        return resultReader == null ? super.getTableResultColumnMetadataOrNull(index) : ColumnReaders.scalarMetadata(index, resultReader);
    }

    @Override
    public SQLSelectAsExpression as(String alias) {
        var resultReader = columnReader();
        if (resultReader != null) {
            // Draft only picks column metadata for SQLColumn. Reuse its public reader SPI for computed expressions.
            String key = alias == null ? getValue() : alias;
            ColumnReaders.setAutomatic(scope.context().getEntityExpressionBuilder(), key, resultReader);
        }
        return super.as(alias);
    }
}
