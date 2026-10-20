package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.expression.lambda.SQLFuncExpression1;
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.grouping.proxy.AbstractGroupingProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.ProGroups;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.group.GroupingSet;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.GroupOperations;

import java.util.List;

/**
 * HANA 原生分组扩展；共同的 ROLLUP 与 mask 继续由 ProGroups 提供。
 */
public class HanaGroups extends ProGroups {
    protected HanaGroups() {
    }

    /**
     * 对已声明的分组键选择多组汇总组合，立即投影为独立报告关系。
     * selector 使用当前 g.keyN() 构造 GroupingSet.of(...)，GroupingSet.total() 表示 ()。
     * 成员及键不能重复；不使用 SQL 字符串或按字段名字猜测键归属。
     *
     * <p>本入口保留数据库原生空集语义，HANA 的空输入不产生总计行。
     * NULL 键需配合 ProGroups.mask 区分真实 NULL 与小计占位；Draft/DTO 读取规则与共同 ROLLUP 相同。
     */
    public static <G extends AbstractGroupingProxy<G, T, S>, T, S, R extends ProxyEntity<R, E>, E>
    EntityQueryable<R, E> groupingSets(EntityQueryable<G, T> grouped, SQLFuncExpression1<G, List<GroupingSet>> selector,
                                       SQLFuncExpression1<G, R> projection) {
        return GroupOperations.groupingSets(grouped, SqlDialect.HANA, selector, projection);
    }
}
