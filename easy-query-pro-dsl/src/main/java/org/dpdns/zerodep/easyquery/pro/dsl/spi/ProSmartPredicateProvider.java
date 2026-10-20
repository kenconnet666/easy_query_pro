package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.expression.predicate.DefaultSmartPredicateAnonymousExpressionBuilderProvider;
import com.easy.query.core.expression.segment.condition.PredicateSegment;
import com.easy.query.core.expression.sql.builder.AnonymousEntityTableExpressionBuilder;

/**
 * 保留窗口及扩展分组结果外层的筛选位置，其他查询继续使用上游 SMART 谓词优化。
 * 即使筛选的是普通日期或地区键，也可能改变另一列全局窗口的输入，因此整层窗口都应保留边界。
 * 检查只覆盖带 Pro 标记的窗口；未提供 metadata 的上游窗口需由调用方显式建立 CTE 边界。
 */
public class ProSmartPredicateProvider extends DefaultSmartPredicateAnonymousExpressionBuilderProvider {
    @Override
    public void process(AnonymousEntityTableExpressionBuilder relation, PredicateSegment predicates) {
        var inner = relation.getEntityQueryExpressionBuilder();
        // 汇总后筛选只决定展示哪些明细/小计；提前筛选会改变总计输入，并重新生成已被排除的汇总行。
        if (inner.hasGroup() && ExpressionScope.containsNullableGrouping(inner.getGroup())) return;
        // 不能只阻止对窗口结果列的筛选：对普通键的筛选同样可能改变全局总额或累计值。
        if (inner.getProjects().getSQLSegments().stream().anyMatch(ExpressionScope::containsWindow))
            return;
        super.process(relation, predicates);
    }
}
