package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;

import java.util.function.Function;

/**
 * Creates a typed expression while the mounting DSL owns SQL construction, binding and scope checks.
 * Implementations must preserve the supplied scope, mapping source and function.
 * Custom Java carriers still require the corresponding upstream JDBC handler and scalar metadata registration.
 * Mounting methods relate E to T; repeating that dependent bound here prevents Java 17 from inferring implicit lambdas.
 */
@FunctionalInterface
public interface ExpressionFactory<T, E extends PropTypeColumn<?>> {
    E create(ExpressionScope scope, PropTypeColumn<T> mappingSource, Function<SQLFunc, SQLFunction> expression);
}
