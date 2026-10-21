package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.basic.jdbc.executor.internal.common.SQLRewriteUnit;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.sql.ToTableContext;
import com.easy.query.core.expression.sql.builder.ExpressionContext;

import java.util.List;
import java.util.Objects;

/**
 * 在 HANA 的同一次 SQL 渲染中保留最外层查询语义。
 *
 * <p>上游 clone 会分别复制内外层 ExpressionContext；后续添加分片 JOIN 只更新外层。
 * 依赖完整数据库关系的内部来源不能因此漏检。此包装不拥有参数、别名或计数器，
 * 所有状态都委托给原 ToSQLContext；CTE、UNION 和子查询自然沿同一次渲染继承外层标记。
 */
public final class HanaQuerySQLContext implements ToSQLContext {
    private final ToSQLContext delegate;
    private final boolean sharding;

    private HanaQuerySQLContext(ToSQLContext delegate, boolean sharding) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sharding = sharding;
    }

    /**
     * 首次 HANA 查询建立标记；子查询必须保留外层标记，不能用自己的上下文覆盖。
     */
    public static ToSQLContext withQueryContext(ToSQLContext context, ExpressionContext expression) {
        return context instanceof HanaQuerySQLContext ? context : new HanaQuerySQLContext(context, expression.isSharding());
    }

    public static boolean isSharding(ToSQLContext context) {
        return context instanceof HanaQuerySQLContext && ((HanaQuerySQLContext) context).sharding;
    }

    @Override
    public int expressionInvokeCountGetIncrement() {
        return delegate.expressionInvokeCountGetIncrement();
    }

    @Override
    public int currentInvokeCount() {
        return delegate.currentInvokeCount();
    }

    @Override
    public void addParameter(SQLParameter parameter) {
        delegate.addParameter(parameter);
    }

    @Override
    public List<SQLParameter> getParameters() {
        return delegate.getParameters();
    }

    @Override
    public SQLRewriteUnit getSQLRewriteUnit() {
        return delegate.getSQLRewriteUnit();
    }

    @Override
    public String getAlias(TableAvailable table) {
        return delegate.getAlias(table);
    }

    @Override
    public ToTableContext getToTableContext() {
        return delegate.getToTableContext();
    }

    @Override
    public void setTableAlias(TableAvailable table, String alias) {
        delegate.setTableAlias(table, alias);
    }
}
