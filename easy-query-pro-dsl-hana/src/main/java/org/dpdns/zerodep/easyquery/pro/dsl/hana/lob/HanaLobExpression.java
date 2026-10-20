package org.dpdns.zerodep.easyquery.pro.dsl.hana.lob;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ScalarComparisonRestriction;

/**
 * A LOB value omits ordinary string/comparison methods and rejects the inherited ordering operations.
 */
public interface HanaLobExpression<T> extends PropTypeColumn<T>, DSLSQLFunctionAvailable, ScalarComparisonRestriction {
    @Override
    default String scalarComparisonError() {
        return "HANA LOB values require an explicit scalar conversion before comparison; use isSqlNull for NULL checks";
    }

    HanaTypes.Lob<?> sqlType();

    BooleanTypeExpression<Boolean> isSqlNull();
}
