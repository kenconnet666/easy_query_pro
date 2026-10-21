package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.core.proxy.ProxyEntity;

import java.util.List;
import java.util.function.Supplier;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;

/**
 * 已部署 SQLScript 表函数的类型化查询来源，仅处理可绑定为单个 JDBC 值的输入。
 *
 * <p>使用命名实参而非位置实参，省略 optional 输入时由数据库函数的 DEFAULT 决定结果，
 * 因此中间参数缺省不会使后续实参错位。显式 SQL NULL 与缺省保持不同，不能相互替换。
 * 声明名必须与函数实际形参大小写相同；函数、schema 和形参分别作为标识符引用。
 *
 * <p>表函数与计算视图共享参数校验和新代理生命周期，不共享 PLACEHOLDER 语法。
 * 本入口不提供表参数、SQL 数组或关联外层列的绑定协议，也不执行部署、授权或独立连接操作。
 * 定义可复用，每次 proxyFactory 必须返回新的未绑定代理；返回原 EntityQueryable 继续组合查询。
 *
 * @param <P> 输出代理类型，保留字段 DSL 与既有查询泛型
 * @param <T> 表函数输出实体或 DTO 类型
 */
public class HanaTableFunction<P extends ProxyEntity<P, T>, T> {
    private final HanaQuerySource<P, T> source;

    protected HanaTableFunction(String schema, String function, Supplier<P> proxyFactory, List<HanaInput<?>> inputs) {
        source = new HanaQuerySource<>(HanaQuerySource.Kind.TABLE_FUNCTION, schema, function, proxyFactory, inputs);
    }

    /**
     * 参数声明顺序决定生成 SQL 和 JDBC 值的顺序；调用方可以按任意顺序提供这些声明的绑定。
     */
    public static <P extends ProxyEntity<P, T>, T> HanaTableFunction<P, T> of(
            String schema, String function, Supplier<P> proxyFactory, HanaInput<?>... inputs) {
        return new HanaTableFunction<>(schema, function, proxyFactory, ImmutableCollections.listOf(inputs));
    }

    /**
     * 创建尚未访问数据库的新查询，缺少必填项、错库或重复/外来声明在此拒绝。
     * 即使没有显式实参也保留函数调用括号；不把缺省输入填成 NULL 或 SQL DEFAULT token。
     */
    public EntityQueryable<P, T> query(EasyQueryClient client, HanaInput.Binding<?>... bindings) {
        return source.query(client, bindings);
    }
}
