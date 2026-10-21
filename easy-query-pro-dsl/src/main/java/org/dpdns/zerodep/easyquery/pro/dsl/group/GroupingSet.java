package org.dpdns.zerodep.easyquery.pro.dsl.group;

import com.easy.query.core.proxy.PropTypeColumn;

import java.util.List;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;

/**
 * 一个原生 GROUPING SETS 成员，键必须直接取自当前分组代理的 keyN()。
 *
 * <p>对象只保存键的引用，SQL 仍由原查询的分组 segment 构造。不能用同名的另一张表的列替代，
 * 也不接受 SQL 字符串；空成员表示总计，而空成员列表不是合法的 GROUPING SETS。
 */
public class GroupingSet {
    private final List<PropTypeColumn<?>> keys;

    protected GroupingSet(List<PropTypeColumn<?>> keys) {
        this.keys = ImmutableCollections.listCopyOf(keys);
    }

    /**
     * 按声明顺序选择本组保留的键；重复键会在挂接查询时被拒绝。
     */
    public static GroupingSet of(PropTypeColumn<?>... keys) {
        return new GroupingSet(ImmutableCollections.listOf(keys));
    }

    /**
     * 生成空分组成员 ()，请求整个输入的总计。空输入行为由所选方言决定。
     */
    public static GroupingSet total() {
        return new GroupingSet(ImmutableCollections.listOf());
    }

    public List<PropTypeColumn<?>> keys() {
        return keys;
    }
}
