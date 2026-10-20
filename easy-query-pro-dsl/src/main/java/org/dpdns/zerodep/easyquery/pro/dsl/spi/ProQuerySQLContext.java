package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.basic.jdbc.executor.internal.common.SQLRewriteUnit;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.sql.ToTableContext;
import com.easy.query.core.expression.sql.builder.ExpressionContext;

import java.util.List;
import java.util.Objects;

/**
 * 为使用 Pro 查询工厂的方言保留本次最外层查询标记，不复制上游参数、别名和根调用计数。
 *
 * <p>内外层 builder 的 clone 可能产生不同 ExpressionContext；内部数据库专属来源不能只检查旧内层，
 * 否则外层后来增加的分片 JOIN 会绕过约束。HANA adapter 自有同类上下文，保持其 sql-core 单向依赖。
 */
public final class ProQuerySQLContext implements ToSQLContext {
    private final ToSQLContext delegate;
    private final boolean sharding;

    private ProQuerySQLContext(ToSQLContext delegate, boolean sharding) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sharding = sharding;
    }

    /**
     * 只在最外层采样；嵌套查询不能用自己的非分片状态重置已经建立的外层标记。
     */
    public static ToSQLContext withQueryContext(ToSQLContext context, ExpressionContext expression) {
        return context instanceof ProQuerySQLContext ? context : new ProQuerySQLContext(context, expression.isSharding());
    }

    public static boolean isSharding(ToSQLContext context) {
        return context instanceof ProQuerySQLContext pro && pro.sharding;
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
