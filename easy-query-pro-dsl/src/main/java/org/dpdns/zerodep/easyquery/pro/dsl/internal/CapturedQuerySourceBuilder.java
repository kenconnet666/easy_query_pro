package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.basic.api.select.ClientQueryable;
import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.segment.SQLNativeSegment;
import com.easy.query.core.expression.segment.scec.context.SQLNativeExpressionContextImpl;
import com.easy.query.core.expression.sql.builder.EntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import com.easy.query.core.expression.sql.builder.SQLAnonymousEntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.impl.QueryExpressionBuilder;
import com.easy.query.core.expression.sql.expression.EntityQuerySQLExpression;
import com.easy.query.core.expression.sql.expression.impl.AnonymousEntityQuerySQLExpressionImpl;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.core.expression.visitor.TableVisitor;
import com.easy.query.core.util.EasySQLExpressionUtil;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 方言模块共用的已投影查询来源边界，不改变来源本身的 SQL 语义或执行方式。
 *
 * <p>构造时仅克隆 ClientQueryable 并提取依赖，保留原 SQLParameter/handler；不提前渲染 SQL 后重绑值。
 * 空 projects 和零 tables 使外层展示谓词不能由 SMART 下推到已完成的计算层。
 * 方言的来源约束、评分和最外层查询检查仍由调用方负责。
 */
public final class CapturedQuerySourceBuilder extends QueryExpressionBuilder implements SQLAnonymousEntityQueryExpressionBuilder {
    private final ClientQueryable<?> source;
    private final SQLNativeSegment sourceSegment;
    private final BiConsumer<ExpressionContext, ToSQLContext> renderCheck;

    public CapturedQuerySourceBuilder(ClientQueryable<?> source, ExpressionContext context, Class<?> resultClass,
                                      BiConsumer<ExpressionContext, ToSQLContext> renderCheck) {
        super(context, resultClass);
        this.source = Objects.requireNonNull(source, "source").cloneQueryable();
        this.renderCheck = Objects.requireNonNull(renderCheck, "renderCheck");
        var nativeContext = new SQLNativeExpressionContextImpl(context, runtimeContext);
        // extract 必须发生在外层 ToSQLContext 建立之前，确保 CTE/表别名依赖已经就绪。
        nativeContext.expression(this.source);
        String alias = runtimeContext.getQueryConfiguration().getDialect().getQuoteName("_EQP_SOURCE");
        sourceSegment = runtimeContext.getSQLSegmentFactory().createSQLNativeSegment(context, "SELECT * FROM {0} " + alias, nativeContext);
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public EntityQuerySQLExpression toExpression() {
        return new CapturedExpression(new EntitySQLExpressionMetadata(expressionContext, runtimeContext), sourceSegment, renderCheck);
    }

    @Override
    public EntityQueryExpressionBuilder cloneEntityExpressionBuilder() {
        return new CapturedQuerySourceBuilder(source, expressionContext.cloneExpressionContext(), queryClass, renderCheck);
    }

    @Override
    public void accept(TableVisitor visitor) {
        source.getSQLEntityExpressionBuilder().accept(visitor);
    }

    private static final class CapturedExpression extends AnonymousEntityQuerySQLExpressionImpl {
        private final EntitySQLExpressionMetadata metadata;
        private final SQLNativeSegment source;
        private final BiConsumer<ExpressionContext, ToSQLContext> renderCheck;

        private CapturedExpression(EntitySQLExpressionMetadata metadata, SQLNativeSegment source,
                                   BiConsumer<ExpressionContext, ToSQLContext> renderCheck) {
            super(metadata, "", List.of());
            this.metadata = metadata;
            this.source = source;
            this.renderCheck = renderCheck;
        }

        @Override
        public String toSQL(ToSQLContext context) {
            renderCheck.accept(metadata.getExpressionContext(), context);
            EasySQLExpressionUtil.expressionInvokeRoot(context);
            return source.toSQL(context);
        }

        @Override
        public EntityQuerySQLExpression cloneSQLExpression() {
            return new CapturedExpression(metadata, source.cloneSQLColumnSegment(), renderCheck);
        }
    }
}
