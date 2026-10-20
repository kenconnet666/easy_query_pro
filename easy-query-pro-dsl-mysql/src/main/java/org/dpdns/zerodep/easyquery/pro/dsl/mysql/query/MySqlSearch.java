package org.dpdns.zerodep.easyquery.pro.dsl.mysql.query;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.api.proxy.entity.select.impl.EasyEntityQueryable;
import com.easy.query.core.basic.api.cte.CteTableAvailable;
import com.easy.query.core.basic.api.select.ClientQueryable;
import com.easy.query.core.enums.ContextTypeEnum;
import com.easy.query.core.expression.lambda.SQLFuncExpression1;
import com.easy.query.core.expression.segment.condition.AndPredicateSegment;
import com.easy.query.core.expression.segment.scec.context.SQLNativeExpressionContext;
import com.easy.query.core.expression.sql.builder.AnonymousEntityTableExpressionBuilder;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.util.HashSet;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 同时安装 MATCH 过滤和分数投影，再以原 EntityQueryable 暴露结果。
 *
 * <p>先安装 MySqlExtensions.configure 与显式 configureQueryScope；缺少根上下文传播时在生成 SQL 阶段失败。
 * 来源只接受未投影、未分组的单物理表 String 映射列；索引列集合与停用词等行为由 MySQL 校验。
 * InnoDB 全文索引在提交时更新，不承诺同一事务中立即搜索到尚未提交的新文档。
 *
 * <p>输出 DTO 用 {@code @Column("SEARCH_SCORE")} 声明 Double 字段。分数保留原生浮点语义，
 * 布尔模式可包含负贡献，匹配谓词不会被改成 score &gt; 0。
 */
public final class MySqlSearch {
    private static final String SCORE_COLUMN = "SEARCH_SCORE";
    private final ClientQueryable<?> searched;

    private MySqlSearch(ClientQueryable<?> searched) {
        this.searched = searched;
    }

    public static <P extends ProxyEntity<P, T>, T> MySqlSearch from(
            EntityQueryable<P, T> source, SQLFuncExpression1<P, MySqlSearchSpec> specification) {
        Objects.requireNonNull(source, "source");
        var builder = source.getClientQueryable().getSQLEntityExpressionBuilder();
        var runtime = builder.getRuntimeContext();
        if (runtime.getService(SqlDialect.class) != SqlDialect.MYSQL)
            throw new IllegalArgumentException("MySQL full-text search requires a MySQL client");
        if (builder.getExpressionContext().isSharding())
            throw new UnsupportedOperationException("MySQL full-text search requires one database without shard merging");
        if (source.getClientQueryable() instanceof CteTableAvailable || builder.getTables().size() != 1
                || builder.hasRelationTables() || builder.getFromTable() instanceof AnonymousEntityTableExpressionBuilder
                || builder.getFromTable().getEntityTable().isAnonymous() || builder.getProjects().isNotEmpty()
                || builder.hasGroup() || builder.hasHaving() || builder.isDistinct())
            throw new IllegalArgumentException("MySQL full-text search requires an unprojected single physical table");
        var spec = Objects.requireNonNull(Objects.requireNonNull(specification, "specification").apply(source.get1Proxy()), "specification result");
        var table = builder.getFromTable().getEntityTable();
        var properties = new HashSet<String>();
        for (var column : spec.columns()) {
            if (column instanceof DSLSQLFunctionAvailable || column.getTable() != table || column.getValue() == null)
                throw new IllegalArgumentException("MySQL full-text search requires physical mapped columns of the current source");
            var metadata = table.getEntityMetadata().getColumnNotNull(column.getValue());
            if (metadata.getPropertyType() != String.class || metadata.getColumnValueSQLConverter() != null)
                throw new IllegalArgumentException("MySQL full-text columns must map String without a SQL value converter");
            if (!properties.add(column.getValue()))
                throw new IllegalArgumentException("Duplicate MySQL full-text column: " + column.getValue());
        }
        if (table.getEntityMetadata().getProperty2ColumnMap().values().stream().anyMatch(column -> column.getName().equalsIgnoreCase(SCORE_COLUMN)))
            throw new IllegalArgumentException("MySQL search source conflicts with generated column " + SCORE_COLUMN);
        var snapshot = source.getClientQueryable().cloneQueryable();
        var where = snapshot.getSQLEntityExpressionBuilder().getWhere();
        if (where.isNotEmpty()) {
            // 保留原 WHERE 整体，防止根级 OR 分支绕过新增 MATCH。
            var existing = new AndPredicateSegment(false);
            where.copyTo(existing);
            where.reset();
            where.addPredicateSegment(existing);
        }
        String columns = IntStream.range(0, spec.columns().size()).mapToObj(index -> "{" + index + "}").collect(Collectors.joining(","));
        String match = "MATCH(" + columns + ") AGAINST({" + spec.columns().size() + "} IN " + spec.mode() + " MODE)";
        snapshot.where(filter -> filter.getSQLNative().sqlNativeSegment(match, context -> bind(context, spec)));
        snapshot.select(selection -> {
            selection.columnAll();
            selection.getSQLNative().sqlNativeSegment(match, context -> {
                bind(context, spec);
                context.setAlias(SCORE_COLUMN);
            });
        });
        return new MySqlSearch(snapshot);
    }

    /**
     * 输出采用 fresh Proxy 与独立 DTO 映射；源临时 reader 不传到输出同名属性。
     */
    public <P extends ProxyEntity<P, T>, T> EntityQueryable<P, T> query(Supplier<P> proxyFactory) {
        var proxy = Objects.requireNonNull(Objects.requireNonNull(proxyFactory, "proxyFactory").get(), "proxyFactory result");
        if (proxy.getTableOrNull() != null)
            throw new IllegalArgumentException("The search result factory must return a fresh unbound proxy");
        var runtime = searched.getRuntimeContext();
        var metadata = runtime.getEntityMetadataManager().getEntityMetadata(proxy.getEntityClass());
        var score = metadata.getProperty2ColumnMap().values().stream().filter(column -> column.getName().equalsIgnoreCase(SCORE_COLUMN)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The search result must map " + SCORE_COLUMN + " to a Double property"));
        if (score.getPropertyType() != Double.class)
            throw new IllegalArgumentException("The search result " + SCORE_COLUMN + " property must use Double");
        var context = runtime.getExpressionBuilderFactory().createExpressionContext(runtime, ContextTypeEnum.QUERY);
        var builder = new MySqlSearchQueryBuilder(searched.cloneQueryable(), context, proxy.getEntityClass());
        return new EasyEntityQueryable<>(proxy, runtime.getSQLClientApiFactory().createQueryable(proxy.getEntityClass(), builder));
    }

    private static void bind(SQLNativeExpressionContext context, MySqlSearchSpec spec) {
        spec.columns().forEach(column -> context.expression(column.getTable(), column.getValue()));
        context.value(spec.searchQuery());
    }
}
