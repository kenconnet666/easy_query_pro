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
 * Retains the upstream segment implementations and rejects a known invalid window count optimization.
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
            // A scalar subquery has its own window evaluation level; ordinary table visitors still enter it.
            if (!(visitor instanceof ExpressionScope.WindowVisitor)) super.accept(visitor);
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
            if (visitor instanceof ExpressionScope.WindowVisitor) {
                var sameLevel = expression.getExpressions().stream().filter(argument -> !(argument instanceof SubQueryParamExpression)).toList();
                EasySQLSegmentUtil.paramExpressionTableVisit(sameLevel, visitor);
            } else delegate.accept(visitor);
        }
    }
}
