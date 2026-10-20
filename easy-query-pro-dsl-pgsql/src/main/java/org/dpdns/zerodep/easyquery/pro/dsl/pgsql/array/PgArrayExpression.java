package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.AnyTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;

import java.util.List;

/**
 * E is the SQL element's Java type; T is the complete column's existing Java mapping.
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
     * Total element count; zero for an empty array and SQL NULL for a NULL array.
     */
    NumberTypeExpression<Integer> cardinality();

    /**
     * Native dimension length; SQL NULL for an empty or absent dimension.
     */
    NumberTypeExpression<Integer> dimensionLength(int dimension);

    /**
     * Native SQL subscript, not a zero-based Java list offset. Out-of-range access returns SQL NULL.
     */
    AnyTypeExpression<E> elementAtSqlIndex(int index);
}
