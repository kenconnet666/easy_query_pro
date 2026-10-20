package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.expression.segment.SQLSegment;
import com.easy.query.core.expression.segment.SelectCountDistinctSegment;
import com.easy.query.core.expression.segment.factory.DefaultSQLSegmentFactory;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.segment.CloneableSQLSegment;
import com.easy.query.core.expression.segment.SubQueryColumnSegment;
import com.easy.query.core.expression.segment.SQLNativeSegment;
import com.easy.query.core.expression.segment.scec.context.core.SQLNativeExpression;
import com.easy.query.core.expression.segment.scec.expression.SubQueryParamExpression;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.segment.impl.SQLColumnAsSegmentImpl;
import com.easy.query.core.expression.segment.impl.SubQueryColumnSegmentImpl;
import com.easy.query.core.expression.visitor.TableVisitor;
import com.easy.query.core.util.EasySQLSegmentUtil;

import java.util.Collection;

/**
 * 在上游 SQL segment 创建点维护同一查询层的窗口边界。
 *
 * <p>别名包装应透明传递窗口标记，标量子查询则有独立计算层。原生函数参数中的子查询也必须遵守
 * 同一边界，否则外层 DISTINCT count 会误认内层窗口。本类只检查已知语义标记，不解析最终 SQL。
 * HANA 方言复用这些包装，同时继续委托自己的 LOB 返回类型 visitor。
 */
public class ProSQLSegmentFactory extends DefaultSQLSegmentFactory {
    @Override
    public SQLNativeSegment createSQLNativeSegment(ExpressionContext context, String template, SQLNativeExpression expression) {
        return nativeBoundary(super.createSQLNativeSegment(context, template, expression), expression);
    }

    public static SQLNativeSegment nativeBoundary(SQLNativeSegment delegate, SQLNativeExpression expression) {
        return new NativeBoundary(delegate, expression);
    }

    @Override
    public CloneableSQLSegment createSQLColumnAsSegment(CloneableSQLSegment expression, String alias, QueryRuntimeContext runtime) {
        return transparentAlias(expression, alias, runtime);
    }

    @Override
    public SubQueryColumnSegment createSubQueryColumnSegment(TableAvailable table, Query<?> query, String alias, QueryRuntimeContext runtime) {
        return scalarSubquery(table, query, alias, runtime);
    }

    public static CloneableSQLSegment transparentAlias(CloneableSQLSegment expression, String alias, QueryRuntimeContext runtime) {
        return new Alias(expression, alias, runtime);
    }

    public static SubQueryColumnSegment scalarSubquery(TableAvailable table, Query<?> query, String alias, QueryRuntimeContext runtime) {
        return new ScalarSubquery(table, query, alias, runtime);
    }

    @Override
    public SelectCountDistinctSegment createSelectCountDistinctSegment(Collection<SQLSegment> columns) {
        validateDistinctCount(columns);
        return super.createSelectCountDistinctSegment(columns);
    }

    /**
     * 上游单列 DISTINCT count 会改写为 COUNT(DISTINCT expression)。同层窗口既不允许这种嵌套，
     * 又有 NULL 行计数差异；调用方应先 distinct().toCteAs(name)，再在外层 count/page。
     */
    public static void validateDistinctCount(Collection<SQLSegment> columns) {
        if (columns.stream().anyMatch(ExpressionScope::containsWindow)) {
            throw new UnsupportedOperationException("Counting a DISTINCT window projection requires distinct().toCteAs(name) before count/page");
        }
    }

    protected static class Alias extends SQLColumnAsSegmentImpl {
        protected final CloneableSQLSegment expression;
        protected final QueryRuntimeContext runtime;

        protected Alias(CloneableSQLSegment expression, String alias, QueryRuntimeContext runtime) {
            super(expression, alias, runtime);
            this.expression = expression;
            this.runtime = runtime;
        }

        @Override
        public void accept(TableVisitor visitor) {
            EasySQLSegmentUtil.sqlSegmentTableVisit(expression, visitor);
        }

        @Override
        public Alias cloneSQLColumnSegment() {
            return new Alias(expression.cloneSQLColumnSegment(), getAlias(), runtime);
        }
    }

    protected static class ScalarSubquery extends SubQueryColumnSegmentImpl {
        protected final QueryRuntimeContext runtime;

        protected ScalarSubquery(TableAvailable table, Query<?> query, String alias, QueryRuntimeContext runtime) {
            super(table, query, alias, runtime);
            this.runtime = runtime;
        }

        @Override
        public void accept(TableVisitor visitor) {
            // 窗口与分组语义只属于当前层；普通表 visitor 仍须保留关联关系分析。
            if (!(visitor instanceof ExpressionScope.SemanticVisitor)) super.accept(visitor);
        }

        @Override
        public ScalarSubquery cloneSQLColumnSegment() {
            return new ScalarSubquery(getTable(), getSubQuery().cloneQueryable(), getAlias(), runtime);
        }
    }

    protected static class NativeBoundary implements SQLNativeSegment {
        protected final SQLNativeSegment delegate;
        protected final SQLNativeExpression expression;

        protected NativeBoundary(SQLNativeSegment delegate, SQLNativeExpression expression) {
            this.delegate = delegate;
            this.expression = expression;
        }

        @Override
        public String toSQL(ToSQLContext context) {
            return delegate.toSQL(context);
        }

        @Override
        public String getAlias() {
            return delegate.getAlias();
        }

        @Override
        public void setAlias(String alias) {
            delegate.setAlias(alias);
        }

        @Override
        public TableAvailable getTable() {
            return delegate.getTable();
        }

        @Override
        public String getPropertyName() {
            return delegate.getPropertyName();
        }

        @Override
        public NativeBoundary cloneSQLColumnSegment() {
            var copy = delegate.cloneSQLColumnSegment();
            copy.setAlias(getAlias());
            return new NativeBoundary(copy, expression);
        }

        @Override
        public void accept(TableVisitor visitor) {
            if (visitor instanceof ExpressionScope.SemanticVisitor) {
                // 原生 expression(query) 不经过 ScalarSubquery，需在参数节点处单独守住计算层边界。
                var sameLevel = expression.getExpressions().stream().filter(argument -> !(argument instanceof SubQueryParamExpression)).toList();
                EasySQLSegmentUtil.paramExpressionTableVisit(sameLevel, visitor);
            } else delegate.accept(visitor);
        }
    }
}
