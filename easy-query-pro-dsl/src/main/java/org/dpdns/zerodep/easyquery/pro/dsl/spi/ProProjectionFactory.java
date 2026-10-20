package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.basic.api.select.provider.SQLExpressionProvider;
import com.easy.query.core.basic.api.select.provider.SQLExpressionProviderImpl;
import com.easy.query.core.basic.extension.conversion.ColumnReader;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.expression.builder.AsSelector;
import com.easy.query.core.expression.builder.Selector;
import com.easy.query.core.expression.builder.impl.AsSelectorImpl;
import com.easy.query.core.expression.builder.impl.SelectorImpl;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.exception.EasyQueryInvalidOperationException;
import com.easy.query.core.expression.lambda.SQLActionExpression;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.parser.core.base.ColumnAsSelector;
import com.easy.query.core.expression.parser.core.base.ColumnSelector;
import com.easy.query.core.expression.parser.core.base.impl.ColumnAsSelectorImpl;
import com.easy.query.core.expression.parser.core.base.impl.ColumnSelectorImpl;
import com.easy.query.core.expression.parser.factory.DefaultSQLExpressionInvokeFactory;
import com.easy.query.core.expression.segment.FuncColumnSegment;
import com.easy.query.core.expression.segment.SQLSegment;
import com.easy.query.core.expression.segment.builder.SQLBuilderSegment;
import com.easy.query.core.expression.segment.impl.SQLFunctionColumnSegmentImpl;
import com.easy.query.core.expression.sql.builder.EntityQueryExpressionBuilder;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.SQLFunctionTranslateImpl;
import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.metadata.EntityMetadata;

/**
 * Preserves derived Draft readers and classifies known Pro scalars through upstream selector/segment SPI.
 * DTO mappings and explicit valueConvert readers keep their own result contract.
 */
public class ProProjectionFactory extends DefaultSQLExpressionInvokeFactory {
    @Override
    public <T> SQLExpressionProvider<T> createSQLExpressionProvider(int index, EntityQueryExpressionBuilder query) {
        return new Provider<>(this, index, query);
    }

    protected Selector createSelector(EntityQueryExpressionBuilder query, SQLBuilderSegment projects) {
        return new ScalarSelector(query, projects);
    }

    protected AsSelector createAsSelector(EntityQueryExpressionBuilder query, SQLBuilderSegment projects, EntityMetadata result) {
        return new ScalarAsSelector(query, projects, result);
    }

    protected static class Provider<T> extends SQLExpressionProviderImpl<T> {
        protected final ProProjectionFactory factory;
        protected final EntityQueryExpressionBuilder query;
        protected final TableAvailable table;

        protected Provider(ProProjectionFactory factory, int index, EntityQueryExpressionBuilder query) {
            super(index, query);
            this.factory = factory;
            this.query = query;
            this.table = query.getTable(index).getEntityTable();
        }

        @Override
        public ColumnSelector<T> getColumnSelector(SQLBuilderSegment projects) {
            return new ColumnSelectorImpl<>(table, factory.createSelector(query, projects));
        }

        @Override
        public <R> ColumnAsSelector<T, R> getColumnAsSelector(SQLBuilderSegment projects, EntityMetadata result) {
            return new ColumnAsSelectorImpl<>(table, factory.createAsSelector(query, projects, result));
        }
    }

    protected static class ScalarSelector extends SelectorImpl {
        protected ScalarSelector(EntityQueryExpressionBuilder query, SQLBuilderSegment projects) {
            super(query, projects);
        }

        @Override
        public Selector columnFunc(TableAvailable table, SQLFunction function, String propertyAlias) {
            if (!ExpressionScope.preservesRows(function)) return super.columnFunc(table, function, propertyAlias);
            var column = table.getEntityMetadata().getColumnNotNull(propertyAlias);
            var segment = new SQLFunctionTranslateImpl(function).toSQLSegment(expressionContext, table, runtimeContext, column.getName());
            sqlBuilderSegment.append(new ScalarColumn(table, column, runtimeContext, segment, column.getName()));
            return this;
        }
    }

    protected static class ScalarAsSelector extends AsSelectorImpl {
        protected ScalarAsSelector(EntityQueryExpressionBuilder query, SQLBuilderSegment projects, EntityMetadata result) {
            super(query, projects, result);
            ColumnReaders.freezeSources(query);
        }

        @Override
        public AsSelector columnAs(TableAvailable table, String property, String propertyAlias) {
            var reader = ColumnReaders.source(entityQueryExpressionBuilder, table, property);
            var result = super.columnAs(table, property, propertyAlias);
            setReader(propertyAlias == null ? property : propertyAlias, reader);
            return result;
        }

        private void setReader(String property, ColumnReader reader) {
            ColumnReaders.setAutomatic(entityQueryExpressionBuilder, property,
                    ColumnReaders.usesSourceReaders(resultEntityMetadata) ? reader : null);
        }

        @Override
        public AsSelector columnFunc(TableAvailable table, String property, SQLFunction function, String propertyAlias, SQLActionExpression before) {
            if (!ExpressionScope.preservesRows(function)) {
                setReader(propertyAlias == null ? property : propertyAlias, null);
                return super.columnFunc(table, property, function, propertyAlias, before);
            }
            before.apply();
            ColumnMetadata source = table == null || property == null ? null : table.getEntityMetadata().getColumnNotNull(property);
            if (source == null && (propertyAlias == null || propertyAlias.isBlank()))
                throw new EasyQueryInvalidOperationException("A computed projection requires a property alias");
            var result = propertyAlias == null && source != null ? null : getResultColumnName(propertyAlias);
            String alias = result == null ? source.getName() : result.getColumnAsName();
            var reader = source == null ? null : ColumnReaders.source(entityQueryExpressionBuilder, table, property);
            setReader(propertyAlias == null ? property : propertyAlias, ExpressionScope.isRowLocal(function) ? reader : ColumnReaders.nullable(reader));
            return appendScalar(table, source != null ? source : result.getColumnMetadata(), function, alias);
        }

        @Override
        public AsSelector columnFunc(TableAvailable table, SQLFunction function, String propertyAlias) {
            if (!ExpressionScope.preservesRows(function)) {
                setReader(propertyAlias, null);
                return super.columnFunc(table, function, propertyAlias);
            }
            var result = propertyAlias == null ? null : getResultColumnName(propertyAlias);
            setReader(propertyAlias, null);
            return appendScalar(table, result == null ? null : result.getColumnMetadata(), function, result == null ? null : result.getColumnAsName());
        }

        protected AsSelector appendScalar(TableAvailable table, ColumnMetadata column, SQLFunction function, String alias) {
            var segment = new SQLFunctionTranslateImpl(function).toSQLSegment(expressionContext, table, runtimeContext, alias);
            sqlBuilderSegment.append(new ScalarColumn(table, column, runtimeContext, segment, alias));
            return this;
        }
    }

    protected static class ScalarColumn extends SQLFunctionColumnSegmentImpl {
        private final SQLSegment expression;

        protected ScalarColumn(TableAvailable table, ColumnMetadata column, QueryRuntimeContext runtime, SQLSegment expression, String alias) {
            super(table, column, runtime, expression, AggregationType.UNKNOWN, alias);
            this.expression = expression;
        }

        @Override
        public boolean isAggregateColumn() {
            return false;
        }

        @Override
        public FuncColumnSegment cloneSQLColumnSegment() {
            return recreate(expression);
        }

        protected ScalarColumn recreate(SQLSegment expression) {
            return new ScalarColumn(table, columnMetadata, runtimeContext, expression, alias);
        }
    }
}
