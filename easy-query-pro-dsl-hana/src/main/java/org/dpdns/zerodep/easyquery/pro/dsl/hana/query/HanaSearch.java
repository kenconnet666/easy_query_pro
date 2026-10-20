package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.api.proxy.entity.select.impl.EasyEntityQueryable;
import com.easy.query.core.basic.api.select.ClientQueryable;
import com.easy.query.core.basic.api.cte.CteTableAvailable;
import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.enums.ContextTypeEnum;
import com.easy.query.core.expression.lambda.SQLFuncExpression1;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.segment.condition.AndPredicateSegment;
import com.easy.query.core.expression.sql.builder.AnonymousEntityTableExpressionBuilder;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.SQLColumn;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.CapturedQuerySourceBuilder;
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaQuerySQLContext;

import java.util.HashSet;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 原子地安装物理列 CONTAINS 与同一 SELECT 的 SCORE，结果继续使用原 EntityQueryable。
 *
 * <p>来源限定为未投影、未分组的单物理表查询。WHERE、逻辑删除和来源分页仍沿原查询执行；
 * 派生表、CTE、JOIN、聚合和任意计算列不自动当作可索引的全文来源。后续 JOIN 可在搜索结果上组合。
 * FULLTEXT(ON) 要求数据库上存在适用的真实全文索引，不会降级为 LIKE 或无索引字符串比较。
 *
 * <p>结果 DTO 以 {@code @Column("SEARCH_SCORE")} 映射一个 Double 属性，其他列按原物理列名映射。
 * 整组搜索只产生一个综合分数；没有能被拿到另一查询层使用的裸 SCORE 表达式。
 */
public final class HanaSearch {
    private static final String SCORE_COLUMN = "SEARCH_SCORE";
    private final ClientQueryable<?> searched;

    private HanaSearch(ClientQueryable<?> searched) {
        this.searched = searched;
    }

    public static <P extends ProxyEntity<P, T>, T> HanaSearch from(
            EntityQueryable<P, T> source, SQLFuncExpression1<P, HanaSearchSpec> specification) {
        Objects.requireNonNull(source, "source");
        var builder = source.getClientQueryable().getSQLEntityExpressionBuilder();
        var runtime = builder.getRuntimeContext();
        if (runtime.getService(SqlDialect.class) != SqlDialect.HANA)
            throw new IllegalArgumentException("HANA full-text search requires a HANA client");
        if (builder.getExpressionContext().isSharding())
            throw new UnsupportedOperationException("HANA full-text search requires one database without shard merging");
        // 上游 CTE 仍用普通表 builder，需同时识别公开 CteTableAvailable，不能只查 isAnonymous。
        if (source.getClientQueryable() instanceof CteTableAvailable
                || builder.getTables().size() != 1 || builder.hasRelationTables() || builder.getFromTable() instanceof AnonymousEntityTableExpressionBuilder
                || builder.getFromTable().getEntityTable().isAnonymous()
                || builder.getProjects().isNotEmpty() || builder.hasGroup() || builder.hasHaving() || builder.isDistinct())
            throw new IllegalArgumentException("HANA full-text search requires an unprojected single physical table");
        var table = builder.getFromTable().getEntityTable();
        var spec = Objects.requireNonNull(Objects.requireNonNull(specification, "specification").apply(source.get1Proxy()), "specification result");
        for (var clause : spec.clauses()) {
            var properties = new HashSet<String>();
            for (var column : clause.columns()) {
                requireColumn(column, table);
                if (!properties.add(column.getValue()))
                    throw new IllegalArgumentException("Duplicate HANA full-text column: " + column.getValue());
            }
        }
        if (table.getEntityMetadata().getProperty2ColumnMap().values().stream().anyMatch(column -> column.getName().equals(SCORE_COLUMN)))
            throw new IllegalArgumentException("HANA search source conflicts with generated column " + SCORE_COLUMN);

        // 仅克隆 ClientQueryable，避免重绑定调用者的可变 Proxy；原查询的参数和过滤器保留在同一表层。
        var snapshot = source.getClientQueryable().cloneQueryable();
        var where = snapshot.getSQLEntityExpressionBuilder().getWhere();
        if (where.isNotEmpty()) {
            // 原 WHERE 允许根级 OR。追加 AND 搜索前先整体包组，否则某个 OR 分支会绕过 CONTAINS。
            var existing = new AndPredicateSegment(false);
            where.copyTo(existing);
            where.reset();
            where.addPredicateSegment(existing);
        }
        for (var clause : spec.clauses()) {
            int count = clause.columns().size();
            String columns = IntStream.range(0, count).mapToObj(index -> "{" + index + "}").collect(Collectors.joining(","));
            if (count > 1) columns = "(" + columns + ")";
            String mode = clause.threshold() == null ? "EXACT" : "FUZZY({" + (count + 1) + "})";
            String predicate = "CONTAINS(" + columns + ",{" + count + "}," + mode + ",FULLTEXT(ON))";
            snapshot.where(filter -> filter.sqlNativeSegment(predicate, context -> {
                clause.columns().forEach(column -> context.expression(column.getValue()));
                context.value(clause.searchQuery());
                if (clause.threshold() != null) context.value(clause.threshold());
            }));
        }
        snapshot.select(columns -> {
            columns.columnAll();
            columns.getSQLNative().sqlNativeSegment("SCORE()", context -> context.setAlias(SCORE_COLUMN));
        });
        return new HanaSearch(snapshot);
    }

    /**
     * 创建隔离的搜索结果查询。输出 DTO 使用自己的映射，来源临时 reader 不传播到同名目标属性。
     * 外层筛选、排序、CTE、count/page 都在评分投影之后进行，不重新定义搜索评分的查询层。
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
        var builder = new CapturedQuerySourceBuilder(searched, context, proxy.getEntityClass(), HanaSearch::checkRendering);
        return new EasyEntityQueryable<>(proxy, runtime.getSQLClientApiFactory().createQueryable(proxy.getEntityClass(), builder));
    }

    private static void checkRendering(ExpressionContext expression, ToSQLContext sql) {
        if (expression.isSharding() || HanaQuerySQLContext.isSharding(sql))
            throw new UnsupportedOperationException("HANA full-text search cannot be combined with shard merging");
    }

    private static void requireColumn(SQLColumn<?, String> column, TableAvailable source) {
        if (column instanceof DSLSQLFunctionAvailable || column.getTable() != source || column.getValue() == null)
            throw new IllegalArgumentException("HANA full-text search requires physical mapped columns of the current source");
        var metadata = source.getEntityMetadata().getColumnNotNull(column.getValue());
        if (metadata.getPropertyType() != String.class || metadata.getColumnValueSQLConverter() != null)
            throw new IllegalArgumentException("HANA full-text columns must map String without a SQL value converter");
    }
}
