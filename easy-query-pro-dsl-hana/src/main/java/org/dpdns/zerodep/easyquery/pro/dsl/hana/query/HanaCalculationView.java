package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.api.proxy.entity.select.impl.EasyEntityQueryable;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.core.proxy.ProxyEntity;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final HanaSQLKeyword KEYWORDS = new HanaSQLKeyword();
    private final String qualifiedName;
    private final Supplier<P> proxyFactory;
    private final Map<String, HanaInput<?>> inputs;

    protected HanaCalculationView(String schema, String view, Supplier<P> proxyFactory, List<HanaInput<?>> inputs) {
        qualifiedName = KEYWORDS.quoteUnquotedName(schema) + "." + KEYWORDS.quoteUnquotedName(view);
        this.proxyFactory = Objects.requireNonNull(proxyFactory, "proxyFactory");
        var declarations = new LinkedHashMap<String, HanaInput<?>>();
        for (var input : inputs) {
            Objects.requireNonNull(input, "input");
            if (declarations.putIfAbsent(input.name(), input) != null)
                throw new IllegalArgumentException("Duplicate HANA input declaration: " + input.name());
        }
        this.inputs = Collections.unmodifiableMap(declarations);
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
        Objects.requireNonNull(client, "client");
        SqlDialect dialect;
        try {
            dialect = client.getRuntimeContext().getService(SqlDialect.class);
        } catch (IllegalArgumentException absent) {
            throw new IllegalStateException("Install HanaExtensions before querying a calculation view", absent);
        }
        if (dialect != SqlDialect.HANA)
            throw new IllegalArgumentException("HANA calculation views require a HANA client");

        var supplied = new LinkedHashMap<String, HanaInput.Binding<?>>();
        for (var binding : Objects.requireNonNull(bindings, "bindings")) {
            Objects.requireNonNull(binding, "binding");
            var declaration = inputs.get(binding.input().name());
            if (declaration != binding.input())
                throw new IllegalArgumentException("Use the declared input of " + qualifiedName + ": " + binding.input().name());
            if (supplied.putIfAbsent(declaration.name(), binding) != null)
                throw new IllegalArgumentException("Duplicate HANA input binding: " + declaration.name());
        }

        var placeholders = new ArrayList<String>();
        var values = new ArrayList<Object>();
        for (var input : inputs.values()) {
            var binding = supplied.get(input.name());
            if (binding == null) {
                if (input.isRequired())
                    throw new IllegalArgumentException("Missing required input of " + qualifiedName + ": " + input.name());
                continue;
            }
            placeholders.add("PLACEHOLDER." + KEYWORDS.quoteUnquotedName("$$" + input.name() + "$$") + " => ?");
            values.add(binding.value());
        }
        String arguments = placeholders.isEmpty() ? "" : " (" + String.join(",", placeholders) + ")";
        String sql = "SELECT * FROM " + qualifiedName + arguments;
        var proxy = Objects.requireNonNull(proxyFactory.get(), "proxyFactory result");
        if (proxy.getTableOrNull() != null)
            throw new IllegalArgumentException("The calculation view factory must return a fresh unbound proxy");
        // NULL 是合法的显式输入，不能用 List.copyOf；列表冻结后交给上游匿名来源与参数机制。
        var parameters = Collections.unmodifiableList(values);
        return new EasyEntityQueryable<>(proxy, client.queryable(sql, proxy.getEntityClass(), parameters));
    }
}
