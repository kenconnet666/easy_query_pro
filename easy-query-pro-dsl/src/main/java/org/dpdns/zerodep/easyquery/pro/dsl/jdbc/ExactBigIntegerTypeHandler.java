package org.dpdns.zerodep.easyquery.pro.dsl.jdbc;

import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.types.handler.ObjectTypeHandler;

import java.sql.SQLException;

/**
 * Keeps integer Java results exact when SQL changes the JDBC numeric representation.
 * Inherited parameter binding preserves the driver's BigInteger-specific flags.
 */
public final class ExactBigIntegerTypeHandler extends ObjectTypeHandler {
    @Override
    public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
        var value = result.getBigDecimal(property.getJdbcIndex());
        if (value == null) return null;
        try {
            return value.toBigIntegerExact();
        } catch (ArithmeticException failure) {
            throw new SQLException("Column " + property.getJdbcIndex() + " is not an exact BigInteger", failure);
        }
    }
}
