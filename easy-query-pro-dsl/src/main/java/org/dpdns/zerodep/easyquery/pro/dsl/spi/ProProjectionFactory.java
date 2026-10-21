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
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.util.EasyStringUtil;
import com.easy.query.core.expression.builder.core.ResultColumnInfo;

/**
 * 通过上游 selector/segment SPI 维护 Pro 投影的读取规则和行数语义。
 *
 * <p>上游函数列默认按聚合列处理，UNKNOWN 也不例外。已明确保持行数的 Pro 标量和窗口使用
 * ScalarColumn，防止进入普通聚合合并路径；未知表达式继续委托上游。
 * 窗口虽然保留行数，仍由作用域、SMART 谓词和 DISTINCT count 组件约束其求值位置。
 *
 * <p>显式 valueConvert 与普通 DTO 目标映射保持优先级。自定义 selector 应继承或委托默认实现，
 * 保留读取器所有权、别名解析和行数分类，避免局部覆盖破坏其他数据库的共享契约。
 */
public class ProProjectionFactory extends DefaultSQLExpressionInvokeFactory {
    /**
     * 扩展分组的 select 回调返回后，先将新 Draft 的 primitive 结果槽变为可空载体。
     * 必须在上游读取 Draft 类型数组和创建派生关系之前调用；只改 context 会遗漏 valueN() 的类型。
     * 普通 DTO 不变，已绑定的来源 Draft 不允许原地改写，请重新 Select.DRAFT.of(...)。
     */
    public static void prepareNullableDraft(ProxyEntity<?, ?> projection) {
        ColumnReaders.prepareNullableDraft(projection);
    }

    @Override
    public <T> SQLExpressionProvider<T> createSQLExpressionProvider(int index, EntityQueryExpressionBuilder query) {
        return new Provider<>(this, index, query);
    }

    /**
     * 应用扩展普通 selector 的挂载点；默认 Provider 与上游查询共用此创建链。
     */
    protected Selector createSelector(EntityQueryExpressionBuilder query, SQLBuilderSegment projects) {
        return new ScalarSelector(query, projects);
    }

    /**
     * 应用扩展别名/DTO selector 的挂载点；result 决定是否沿用源读取规则。
     */
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
            ColumnMetadata column = table.getEntityMetadata().getColumnNotNull(propertyAlias);
            SQLSegment segment = new SQLFunctionTranslateImpl(function).toSQLSegment(expressionContext, table, runtimeContext, column.getName());
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
            ColumnReader reader = ColumnReaders.source(entityQueryExpressionBuilder, table, property);
            AsSelector result = super.columnAs(table, property, propertyAlias);
            setReader(propertyAlias == null ? property : propertyAlias, reader);
            return result;
        }

        private void setReader(String property, ColumnReader reader) {
            if (entityQueryExpressionBuilder.hasGroup()
                    && ExpressionScope.containsNullableGrouping(entityQueryExpressionBuilder.getGroup())) {
                reader = ColumnReaders.nullable(reader);
            }
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
            if (source == null && (propertyAlias == null || EasyStringUtil.isBlank(propertyAlias)))
                throw new EasyQueryInvalidOperationException("A computed projection requires a property alias");
            ResultColumnInfo result = propertyAlias == null && source != null ? null : getResultColumnName(propertyAlias);
            String alias = result == null ? source.getName() : result.getColumnAsName();
            ColumnReader reader = source == null ? null : ColumnReaders.source(entityQueryExpressionBuilder, table, property);
            setReader(propertyAlias == null ? property : propertyAlias, ExpressionScope.isRowLocal(function) ? reader : ColumnReaders.nullable(reader));
            return appendScalar(table, source != null ? source : result.getColumnMetadata(), function, alias);
        }

        @Override
        public AsSelector columnFunc(TableAvailable table, SQLFunction function, String propertyAlias) {
            if (!ExpressionScope.preservesRows(function)) {
                setReader(propertyAlias, null);
                return super.columnFunc(table, function, propertyAlias);
            }
            ResultColumnInfo result = propertyAlias == null ? null : getResultColumnName(propertyAlias);
            setReader(propertyAlias, null);
            return appendScalar(table, result == null ? null : result.getColumnMetadata(), function, result == null ? null : result.getColumnAsName());
        }

        protected AsSelector appendScalar(TableAvailable table, ColumnMetadata column, SQLFunction function, String alias) {
            SQLSegment segment = new SQLFunctionTranslateImpl(function).toSQLSegment(expressionContext, table, runtimeContext, alias);
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

        /**
         * 子类可在此保留附加状态与具体类型，避免 clone 后退回基础列实现。
         */
        protected ScalarColumn recreate(SQLSegment expression) {
            return new ScalarColumn(table, columnMetadata, runtimeContext, expression, alias);
        }
    }
}
