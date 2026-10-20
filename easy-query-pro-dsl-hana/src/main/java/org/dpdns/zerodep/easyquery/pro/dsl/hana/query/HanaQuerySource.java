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
 * 两类参数化来源共享声明、绑定及代理生命周期，具体来源只决定参数语法。
 * 这是包内组合基础，避免让表函数继承计算视图的 PLACEHOLDER 语义。
 * SQL 仍交给原 client 的匿名来源，过滤、映射、事务与执行不另起一套实现。
 */
final class HanaQuerySource<P extends ProxyEntity<P, T>, T> {
    enum Kind {
        CALCULATION_VIEW,
        TABLE_FUNCTION
    }

    private static final HanaSQLKeyword KEYWORDS = new HanaSQLKeyword();
    private final Kind kind;
    private final String qualifiedName;
    private final Supplier<P> proxyFactory;
    private final Map<String, HanaInput<?>> inputs;

    HanaQuerySource(Kind kind, String schema, String objectName, Supplier<P> proxyFactory, List<HanaInput<?>> inputs) {
        this.kind = Objects.requireNonNull(kind, "kind");
        qualifiedName = KEYWORDS.quoteUnquotedName(schema) + "." + KEYWORDS.quoteUnquotedName(objectName);
        this.proxyFactory = Objects.requireNonNull(proxyFactory, "proxyFactory");
        var declarations = new LinkedHashMap<String, HanaInput<?>>();
        for (var input : Objects.requireNonNull(inputs, "inputs")) {
            Objects.requireNonNull(input, "input");
            if (declarations.putIfAbsent(input.name(), input) != null)
                throw new IllegalArgumentException("Duplicate HANA input declaration: " + input.name());
        }
        this.inputs = Collections.unmodifiableMap(declarations);
    }

    EntityQueryable<P, T> query(EasyQueryClient client, HanaInput.Binding<?>... bindings) {
        Objects.requireNonNull(client, "client");
        SqlDialect dialect;
        try {
            dialect = client.getRuntimeContext().getService(SqlDialect.class);
        } catch (IllegalArgumentException absent) {
            throw new IllegalStateException("Install HanaExtensions before querying a HANA source", absent);
        }
        if (dialect != SqlDialect.HANA)
            throw new IllegalArgumentException("HANA query sources require a HANA client");

        var supplied = new LinkedHashMap<String, HanaInput.Binding<?>>();
        for (var binding : Objects.requireNonNull(bindings, "bindings")) {
            Objects.requireNonNull(binding, "binding");
            var declaration = inputs.get(binding.input().name());
            if (declaration != binding.input())
                throw new IllegalArgumentException("Use the declared input of " + qualifiedName + ": " + binding.input().name());
            if (supplied.putIfAbsent(declaration.name(), binding) != null)
                throw new IllegalArgumentException("Duplicate HANA input binding: " + declaration.name());
        }

        var arguments = new ArrayList<String>();
        var values = new ArrayList<Object>();
        for (var input : inputs.values()) {
            var binding = supplied.get(input.name());
            if (binding == null) {
                if (input.isRequired())
                    throw new IllegalArgumentException("Missing required input of " + qualifiedName + ": " + input.name());
                continue;
            }
            String parameter = kind == Kind.CALCULATION_VIEW
                    ? "PLACEHOLDER." + KEYWORDS.quoteUnquotedName("$$" + input.name() + "$$")
                    : KEYWORDS.quoteUnquotedName(input.name());
            arguments.add(parameter + " => ?");
            values.add(binding.jdbcValue());
        }
        // 计算视图的缺省输入不需要括号；函数即便没有实参也必须保留调用括号。
        String invocation = arguments.isEmpty() && kind == Kind.CALCULATION_VIEW
                ? "" : " (" + String.join(",", arguments) + ")";
        String sql = "SELECT * FROM " + qualifiedName + invocation;
        var proxy = Objects.requireNonNull(proxyFactory.get(), "proxyFactory result");
        if (proxy.getTableOrNull() != null)
            throw new IllegalArgumentException("The HANA source factory must return a fresh unbound proxy");
        // NULL 是合法的显式输入，不能用 List.copyOf；冻结后交给上游匿名来源与参数机制。
        var parameters = Collections.unmodifiableList(values);
        return new EasyEntityQueryable<>(proxy, client.queryable(sql, proxy.getEntityClass(), parameters));
    }
}
