package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.types.handler.ByteTypeHandler;

import java.sql.SQLException;

/** HANA TINYINT 无符号且 getByte 会回绕，读取 Java Byte 时必须独立检查范围。 */
public final class HanaByteTypeHandler extends ByteTypeHandler {
    @Override
    public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
        var value = result.getBigDecimal(property.getJdbcIndex());
        if (value == null) return property.isPrimitive() ? (byte) 0 : null;
        try {
            return value.byteValueExact();
        } catch (ArithmeticException exception) {
            throw new SQLException("HANA numeric value cannot be represented as Java Byte at column "
                    + property.getJdbcIndex(), "22003", exception);
        }
    }
}
