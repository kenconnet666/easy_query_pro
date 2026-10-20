package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.api.proxy.entity.select.impl.EasyEntityQueryable;
import com.easy.query.core.basic.api.cte.CteTableAvailable;
import com.easy.query.core.basic.api.select.ClientQueryable;
import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.enums.ContextTypeEnum;
import com.easy.query.core.expression.lambda.SQLFuncExpression1;
import com.easy.query.core.expression.segment.condition.AndPredicateSegment;
import com.easy.query.core.expression.sql.builder.AnonymousEntityTableExpressionBuilder;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.CapturedQuerySourceBuilder;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsVector;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProQuerySQLContext;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 同时装配 PostgreSQL @@ 筛选与 rank 投影，再交原 EntityQueryable 继续组合。
 *
 * <p>安装 PgSqlExtensions.configure、configureQueryScope 及 configureTypeHandlers 后使用。
 * 来源限未投影的单物理表 PgTsVector 映射列；推荐 stored generated tsvector + GIN，
 * 索引是否适用、配置对象和词典是否存在仍由 PostgreSQL 判断。
 *
 * <p>输出 DTO 用 {@code @Column("SEARCH_SCORE")} 映射 Double。原生 float4 rank 显式转换
 * 为 double precision 只便于结果类型组合，不恢复计算时已经舍入的精度。
 */
public final class PgSearch {
    private static final String SCORE_COLUMN = "SEARCH_SCORE";
    private final ClientQueryable<?> searched;

    private PgSearch(ClientQueryable<?> searched) {
        this.searched = searched;
    }

    public static <P extends ProxyEntity<P, T>, T> PgSearch from(
            EntityQueryable<P, T> source, SQLFuncExpression1<P, PgSearchSpec> specification) {
        Objects.requireNonNull(source, "source");
        var builder = source.getClientQueryable().getSQLEntityExpressionBuilder();
        var runtime = builder.getRuntimeContext();
        if (runtime.getService(SqlDialect.class) != SqlDialect.POSTGRESQL)
            throw new IllegalArgumentException("PostgreSQL full-text search requires a PostgreSQL client");
        if (builder.getExpressionContext().isSharding())
            throw new UnsupportedOperationException("PostgreSQL full-text search requires one database without shard merging");
        if (source.getClientQueryable() instanceof CteTableAvailable || builder.getTables().size() != 1
                || builder.hasRelationTables() || builder.getFromTable() instanceof AnonymousEntityTableExpressionBuilder
                || builder.getFromTable().getEntityTable().isAnonymous() || builder.getProjects().isNotEmpty()
                || builder.hasGroup() || builder.hasHaving() || builder.isDistinct())
            throw new IllegalArgumentException("PostgreSQL full-text search requires an unprojected single physical table");
        var spec = Objects.requireNonNull(Objects.requireNonNull(specification, "specification").apply(source.get1Proxy()), "specification result");
        var table = builder.getFromTable().getEntityTable();
        var vector = spec.vector();
        if (vector instanceof DSLSQLFunctionAvailable || vector.getTable() != table || vector.getValue() == null)
            throw new IllegalArgumentException("PostgreSQL full-text search requires a physical mapped vector of the current source");
        var column = table.getEntityMetadata().getColumnNotNull(vector.getValue());
        if (column.getPropertyType() != PgTsVector.class || column.getColumnValueSQLConverter() != null)
            throw new IllegalArgumentException("PostgreSQL full-text columns must map PgTsVector without a SQL value converter");
        if (table.getEntityMetadata().getProperty2ColumnMap().values().stream().anyMatch(mapped -> mapped.getName().equals(SCORE_COLUMN)))
            throw new IllegalArgumentException("PostgreSQL search source conflicts with generated column " + SCORE_COLUMN);
        var snapshot = source.getClientQueryable().cloneQueryable();
        var where = snapshot.getSQLEntityExpressionBuilder().getWhere();
        if (where.isNotEmpty()) {
            // 原 WHERE 先作为整体，根级 OR 才不会绕过新加入的全文条件。
            var existing = new AndPredicateSegment(false);
            where.copyTo(existing);
            where.reset();
            where.addPredicateSegment(existing);
        }
        snapshot.where(filter -> filter.getSQLNative().sqlNativeSegment(spec.predicateSql(), context -> spec.bind(context, false)));
        snapshot.select(selection -> {
            selection.columnAll();
            selection.getSQLNative().sqlNativeSegment(spec.scoreSql(), context -> {
                spec.bind(context, true);
                context.setAlias(SCORE_COLUMN);
            });
        });
        return new PgSearch(snapshot);
    }

    /**
     * 独立输出元数据不会继承来源查询的临时 reader；每次调用必须返回新的未绑定 Proxy。
     */
    public <P extends ProxyEntity<P, T>, T> EntityQueryable<P, T> query(Supplier<P> proxyFactory) {
        var proxy = Objects.requireNonNull(Objects.requireNonNull(proxyFactory, "proxyFactory").get(), "proxyFactory result");
        if (proxy.getTableOrNull() != null)
            throw new IllegalArgumentException("The search result factory must return a fresh unbound proxy");
        var runtime = searched.getRuntimeContext();
        var metadata = runtime.getEntityMetadataManager().getEntityMetadata(proxy.getEntityClass());
        var score = metadata.getProperty2ColumnMap().values().stream().filter(column -> column.getName().equals(SCORE_COLUMN)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The search result must map " + SCORE_COLUMN + " to a Double property"));
        if (score.getPropertyType() != Double.class)
            throw new IllegalArgumentException("The search result " + SCORE_COLUMN + " property must use Double");
        var context = runtime.getExpressionBuilderFactory().createExpressionContext(runtime, ContextTypeEnum.QUERY);
        var builder = new CapturedQuerySourceBuilder(searched, context, proxy.getEntityClass(), PgSearch::checkRendering);
        return new EasyEntityQueryable<>(proxy, runtime.getSQLClientApiFactory().createQueryable(proxy.getEntityClass(), builder));
    }

    private static void checkRendering(ExpressionContext expression, ToSQLContext sql) {
        if (!(sql instanceof ProQuerySQLContext))
            throw new IllegalStateException("Install PgSqlExtensions.configureQueryScope and preserve ProQuerySQLContext in custom query factories");
        if (expression.isSharding() || ProQuerySQLContext.isSharding(sql))
            throw new UnsupportedOperationException("PostgreSQL full-text search cannot be combined with shard merging");
    }
}
