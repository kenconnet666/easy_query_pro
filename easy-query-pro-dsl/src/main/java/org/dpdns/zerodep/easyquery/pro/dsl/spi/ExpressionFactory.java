package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;

import java.util.function.Function;

/**
 * 将已经完成 SQL 构造和作用域校验的表达式，挂载为应用自己的具体表达式类型。
 *
 * <p>T 表示完整业务值的 Java 类型，E 表示对外返回的表达式类型。工厂必须继续使用传入的
 * scope、mappingSource 和 expression；不能返回其他查询缓存的表达式，也不能重新从最初实体列
 * 构造 SQL，否则会丢失前面已经组合的运算。自定义 Java 载体仍需注册对应 JDBC handler 和标量元数据。
 *
 * <p>E 与 T 的关联由公共挂载方法约束。本接口有意只声明 {@code E extends PropTypeColumn<?>}：
 * 重复声明依赖 T 的上界会使 Java 17 无法推导部分隐式 lambda，这一点已有消费者正负例保护。
 *
 * @param <T> 来源及结果的业务值类型
 * @param <E> 工厂实际创建的表达式类型，挂载后保留其具体方法
 */
@FunctionalInterface
public interface ExpressionFactory<T, E extends PropTypeColumn<?>> {
    /**
     * @param scope         当前 client 和查询作用域
     * @param mappingSource 本次运算应沿用的映射来源；CAST/新参数可能已经提供新的结果来源
     * @param expression    纯 SQL 构造回调，可能被多次求值，不应执行 JDBC 或依赖一次性副作用
     * @return 使用上述上下文和映射创建的非 null 表达式
     */
    E create(ExpressionScope scope, PropTypeColumn<T> mappingSource, Function<SQLFunc, SQLFunction> expression);
}
