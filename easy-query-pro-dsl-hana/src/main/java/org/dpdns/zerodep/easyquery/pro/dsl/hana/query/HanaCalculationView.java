package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.core.proxy.ProxyEntity;

import java.util.List;
import java.util.function.Supplier;

/**
 * 一个已部署 HANA 计算视图的类型化来源定义。对象部署和授权由应用迁移或 HDI 管理。
 *
 * <p>schema 与 view 分别作为完整标识符引用；输入名称构造成 PLACEHOLDER 标识符，值只进入 JDBC
 * 参数列表。本类不接受任意 SQL 表达式或把参数值拼成 SQL，返回的仍是原 EntityQueryable。
 *
 * <p>定义可复用，查询状态不可共享。proxyFactory 每次必须返回新的未绑定代理，这使同一视图的
 * 两次 query 可以安全充当 JOIN 两侧。结果列使用模型实际列名，HANA 大写带引号列需显式 @Column 映射。
 *
 * @param <P> 输出代理类型，保留现有字段 DSL
 * @param <T> 输出实体或 DTO 类型
 */
public class HanaCalculationView<P extends ProxyEntity<P, T>, T> {
    private final HanaQuerySource<P, T> source;

    protected HanaCalculationView(String schema, String view, Supplier<P> proxyFactory, List<HanaInput<?>> inputs) {
        source = new HanaQuerySource<>(HanaQuerySource.Kind.CALCULATION_VIEW, schema, view, proxyFactory, inputs);
    }

    /**
     * 按模型声明输入；参数输出顺序固定为此处的声明顺序，与 bind 调用顺序无关。
     */
    public static <P extends ProxyEntity<P, T>, T> HanaCalculationView<P, T> of(
            String schema, String view, Supplier<P> proxyFactory, HanaInput<?>... inputs) {
        return new HanaCalculationView<>(schema, view, proxyFactory, List.of(inputs));
    }

    /**
     * 建立一个新的来源查询，尚不访问数据库。
     *
     * <p>缺少必填输入、重复赋值、未声明输入或使用另一个同名声明会提前拒绝。optional 输入缺省时
     * 不生成该 PLACEHOLDER；传 NULL 则保留一个 JDBC 参数，不能据此请求默认值。
     * 后续 where/order/group/CTE/count/page 与事务继续使用同一个 EasyQuery client。
     */
    public EntityQueryable<P, T> query(EasyQueryClient client, HanaInput.Binding<?>... bindings) {
        return source.query(client, bindings);
    }
}
