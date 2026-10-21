package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.expression.lambda.SQLFuncExpression1;
import com.easy.query.core.expression.segment.SQLSegment;
import com.easy.query.core.expression.segment.condition.AndPredicateSegment;
import com.easy.query.core.expression.sql.builder.EntityQueryExpressionBuilder;
import com.easy.query.core.func.SQLFunctionTranslateImpl;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.grouping.proxy.AbstractGroupingProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.group.GroupingSet;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProProjectionFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.easy.query.core.expression.segment.condition.PredicateSegment;
import com.easy.query.core.proxy.core.EntitySQLContext;
import com.easy.query.core.func.SQLFunction;

/**
 * 三个 facade 共用的分组节点装配与投影边界。
 * 保存原键 segment 后组装受控语法，参数和表访问继续委托原节点；不读取或改写最终 SQL 文本。
 */
public final class GroupOperations {
    private GroupOperations() {
    }

    public static <G extends AbstractGroupingProxy<G, T, S>, T, S, R extends ProxyEntity<R, E>, E>
    EntityQueryable<R, E> rollup(EntityQueryable<G, T> grouped, SQLFuncExpression1<G, R> projection) {
        EntityQueryable<G, T> work = grouped.cloneQueryable();
        EntityQueryExpressionBuilder builder = requireUnprojected(work);
        List<SQLSegment> segments = ImmutableCollections.listCopyOf(builder.getGroup().getSQLSegments());
        ExpressionScope scope = scope(work.get1Proxy());
        replaceGroup(builder, scope, "ROLLUP(" + placeholders(segments.size()) + ")", segments);
        if (scope.dialect() == SqlDialect.POSTGRESQL) {
            PredicateSegment having = builder.getHaving();
            if (having.isNotEmpty()) {
                // 原 HAVING 可能以 OR 连接；先保留整个条件的括号，再追加共同空输入约束。
                AndPredicateSegment existing = new AndPredicateSegment(false);
                having.copyTo(existing);
                having.reset();
                having.addPredicateSegment(existing);
            }
            work.having(g -> g.count().gt(0L));
        }
        return project(work, projection);
    }

    public static <G extends AbstractGroupingProxy<G, T, S>, T, S, R extends ProxyEntity<R, E>, E>
    EntityQueryable<R, E> groupingSets(EntityQueryable<G, T> grouped, SqlDialect expected,
                                       SQLFuncExpression1<G, List<GroupingSet>> selector, SQLFuncExpression1<G, R> projection) {
        Objects.requireNonNull(selector, "selector");
        EntityQueryable<G, T> work = grouped.cloneQueryable();
        EntityQueryExpressionBuilder builder = requireUnprojected(work);
        G group = work.get1Proxy();
        ExpressionScope scope = scope(group);
        if (scope.dialect() != expected || expected == SqlDialect.MYSQL)
            throw new IllegalArgumentException("GROUPING SETS requires the matching PostgreSQL or HANA facade");
        List<GroupingSet> sets = ImmutableCollections.listCopyOf(selector.apply(group));
        if (sets.isEmpty()) throw new IllegalArgumentException("GROUPING SETS requires at least one member");
        List<SQLSegment> originals = ImmutableCollections.listCopyOf(builder.getGroup().getSQLSegments());
        ArrayList<SQLSegment> arguments = new ArrayList<SQLSegment>();
        HashSet<List<Integer>> seen = new HashSet<List<Integer>>();
        ArrayList<String> members = new ArrayList<String>();
        for (GroupingSet set : sets) {
            List<Integer> indexes = keyIndexes(group, set.keys());
            // (a,b) 与 (b,a) 属于同一分组集合；重复成员会重复输出行，公共入口明确拒绝这种易误用情况。
            if (!seen.add(indexes.stream().sorted().collect(ImmutableCollections.toList())))
                throw new IllegalArgumentException("GROUPING SETS contains a duplicate member");
            ArrayList<String> member = new ArrayList<String>();
            for (int index : indexes) {
                member.add("{" + arguments.size() + "}");
                arguments.add(originals.get(index));
            }
            members.add("(" + String.join(",", member) + ")");
        }
        replaceGroup(builder, scope, "GROUPING SETS (" + String.join(",", members) + ")", arguments);
        return project(work, projection);
    }

    public static NumberTypeExpression<Integer> mask(AbstractGroupingProxy<?, ?, ?> group, PropTypeColumn<?>... keys) {
        List<PropTypeColumn<?>> selected = ImmutableCollections.listOf(keys);
        if (selected.isEmpty() || selected.size() > 31)
            throw new IllegalArgumentException("A grouping mask requires between 1 and 31 keys");
        keyIndexes(group, selected);
        ExpressionScope scope = scope(group);
        String name = scope.dialect() == SqlDialect.HANA ? "GROUPING_ID" : "GROUPING";
        return new NumberTypeExpressionImpl<>(scope.context(), scope.anchor(), null, factory ->
                scope.function(factory.anySQLFunction(name + "(" + placeholders(selected.size()) + ")", args ->
                        selected.forEach(key -> args.sqlFunc(scope.operand(key, factory)))), AggregationType.UNKNOWN, true),
                Integer.class);
    }

    private static EntityQueryExpressionBuilder requireUnprojected(EntityQueryable<?, ?> query) {
        EntityQueryExpressionBuilder builder = query.getClientQueryable().getSQLEntityExpressionBuilder();
        if (!builder.hasGroup() || builder.getProjects().isNotEmpty()
                || ExpressionScope.containsNullableGrouping(builder.getGroup()))
            throw new IllegalArgumentException("Expand an ordinary groupBy query before selecting or expanding it");
        List<PropTypeColumn<?>> keys = ((AbstractGroupingProxy<?, ?, ?>) query.get1Proxy()).getKeys();
        if (keys.size() != builder.getGroup().getSQLSegments().size())
            throw new IllegalArgumentException("Each declared grouping key must correspond to one group expression");
        return builder;
    }

    private static ExpressionScope scope(AbstractGroupingProxy<?, ?, ?> group) {
        EntitySQLContext context = Objects.requireNonNull(group.getEntitySQLContext(), "A query-bound grouping proxy is required");
        return new ExpressionScope(context, context.getEntityExpressionBuilder().getTable(0).getEntityTable());
    }

    private static List<Integer> keyIndexes(AbstractGroupingProxy<?, ?, ?> group, List<PropTypeColumn<?>> selected) {
        List<PropTypeColumn<?>> declared = group.getKeys();
        ArrayList<Integer> result = new ArrayList<Integer>();
        for (PropTypeColumn<?> key : selected) {
            int index = -1;
            for (int i = 0; i < declared.size(); i++) {
                if (declared.get(i) == key) {
                    index = i;
                    break;
                }
            }
            if (index < 0) throw new IllegalArgumentException("Use keyN() from the current grouping proxy");
            if (result.contains(index))
                throw new IllegalArgumentException("A grouping key cannot occur twice in one member");
            result.add(index);
        }
        return result;
    }

    private static void replaceGroup(EntityQueryExpressionBuilder builder, ExpressionScope scope, String template, List<SQLSegment> arguments) {
        List<SQLSegment> saved = ImmutableCollections.listCopyOf(arguments);
        SQLFunction function = scope.expandingGroup(scope.runtime().fx().anySQLFunction(template, args -> saved.forEach(args::sql)));
        SQLSegment segment = new SQLFunctionTranslateImpl(function).toSQLSegment(builder.getExpressionContext(), scope.anchor(), scope.runtime(), null);
        builder.getGroup().clear();
        builder.getGroup().append(segment);
    }

    private static <G extends AbstractGroupingProxy<G, T, S>, T, S, R extends ProxyEntity<R, E>, E>
    EntityQueryable<R, E> project(EntityQueryable<G, T> query, SQLFuncExpression1<G, R> projection) {
        Objects.requireNonNull(projection, "projection");
        // 显式 select 必须与 GROUP 替换一同完成，否则上游缺省投影会把 ROLLUP 本身放入 SELECT。
        return query.select(group -> {
            R result = Objects.requireNonNull(projection.apply(group), "projection result");
            if (result instanceof AbstractGroupingProxy<?, ?, ?>)
                throw new IllegalArgumentException("Select explicit report columns instead of returning the grouping proxy");
            ProProjectionFactory.prepareNullableDraft(result);
            return result;
        });
    }

    private static String placeholders(int size) {
        return IntStream.range(0, size).mapToObj(i -> "{" + i + "}").collect(Collectors.joining(","));
    }
}
