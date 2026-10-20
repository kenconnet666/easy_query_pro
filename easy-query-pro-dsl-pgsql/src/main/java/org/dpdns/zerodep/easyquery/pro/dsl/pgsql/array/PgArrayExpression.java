package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.AnyTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;

import java.util.List;

/**
 * PostgreSQL 数组操作，区分元素类型和整列业务表示。
 *
 * <p>E 是 SQL 数组元素的 Java 类型；T 是整列原有映射，可为 PgArrayValue、List 或业务对象。
 * view 保留 T 的 reader，不能因为元素是 E 就把整列按 E 读取。元素提取、长度和布尔运算才切换结果类型。
 * 布尔方法返回待组合的 SQL 表达式，不立即修改 WHERE，NULL 结果遵循数据库三值逻辑。
 *
 * @param <E> 已声明的 SQL 元素类型
 * @param <T> 整列值的 Java 业务类型
 */
public interface PgArrayExpression<E, T> extends PropTypeColumn<T>, DSLSQLFunctionAvailable {
    PgArrayType<E> sqlType();

    BooleanTypeExpression<Boolean> anyEq(PropTypeColumn<E> value);

    BooleanTypeExpression<Boolean> anyEqValue(E value);

    BooleanTypeExpression<Boolean> containsAll(PgArrayExpression<E, ?> values);

    BooleanTypeExpression<Boolean> containedBy(PgArrayExpression<E, ?> values);

    BooleanTypeExpression<Boolean> overlaps(PgArrayExpression<E, ?> values);

    BooleanTypeExpression<Boolean> containsAllValues(List<? extends E> values);

    BooleanTypeExpression<Boolean> containedByValues(List<? extends E> values);

    BooleanTypeExpression<Boolean> overlapsValues(List<? extends E> values);

    /**
     * 数组元素总数：空数组为 0，SQL NULL 数组仍返回 NULL，不与空数组合并。
     */
    NumberTypeExpression<Integer> cardinality();

    /**
     * 原生维度长度：空数组或不存在的维度返回 SQL NULL，与 cardinality 的空数组结果不同。
     */
    NumberTypeExpression<Integer> dimensionLength(int dimension);

    /**
     * 使用 PostgreSQL 原生一基下标，越界返回 SQL NULL。
     * 当前载体只接收一维、下界为 1 的数组；不能把 Java List 的零基索引直接传入。
     */
    AnyTypeExpression<E> elementAtSqlIndex(int index);
}
