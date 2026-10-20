package org.dpdns.zerodep.easyquery.pro.dsl;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.expression.lambda.SQLFuncExpression1;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.grouping.proxy.AbstractGroupingProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.GroupOperations;

/**
 * 在上游类型化 groupBy 上附加共同的报表分组能力，不创建另一套 Queryable。
 *
 * <p>先 groupBy 声明键，再调用 rollup 并明确选择输出。返回结果继续使用原有 where、orderBy、
 * toCteAs、count 和分页。多表来源类型也保留，因此 g.groupTable() 仍是上游对应的 MergeTuple。
 */
public class ProGroups {
    protected ProGroups() {
    }

    /**
     * 按已声明键的顺序生成明细、逐级小计和总计，并立即投影为一个独立报告关系。
     *
     * <p>输入查询先 clone，普通分组分支不受影响。共同契约规定空输入返回零行；PG 原生会额外产生
     * 空总计，内部通过 HAVING COUNT(*) > 0 统一，而非在 Java 端删除行或修改分页数量。
     *
     * <p>projection 应返回新 Select.DRAFT.of(...) 或显式 DTO。Draft 的 primitive 输出允许小计 NULL；
     * DTO 继续按自身字段类型读取，需保留 NULL 时请声明包装类型。参数化计算键建议先投影为派生列，
     * 避免同一计算在 GROUP BY 和 GROUPING 中成为不同的 JDBC 参数位置。
     */
    public static <G extends AbstractGroupingProxy<G, T, S>, T, S, R extends ProxyEntity<R, E>, E>
    EntityQueryable<R, E> rollup(EntityQueryable<G, T> grouped, SQLFuncExpression1<G, R> projection) {
        return GroupOperations.rollup(grouped, projection);
    }

    /**
     * 区分原始数据 NULL 与小计占位 NULL：未参与当前分组的参数对应位为 1，最右参数是最低位。
     * 例如两个键的明细为 0、只省略第二键的小计为 1、总计为 3。
     *
     * <p>只接受当前分组的 keyN()，数量为 1..31，统一返回 Integer，避免各库原生位宽差异。
     * HANA 使用 GROUPING_ID，MySQL/PG 使用 GROUPING。它是数据库分组表达式，不能按普通分片合并。
     */
    public static NumberTypeExpression<Integer> mask(AbstractGroupingProxy<?, ?, ?> group, PropTypeColumn<?>... keys) {
        return GroupOperations.mask(group, keys);
    }
}
