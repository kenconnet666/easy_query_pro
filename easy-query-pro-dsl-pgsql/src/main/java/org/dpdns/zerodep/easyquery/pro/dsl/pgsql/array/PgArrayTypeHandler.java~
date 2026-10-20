package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array;

import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import org.postgresql.jdbc.PgArray;

import java.nio.ByteBuffer;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;

/** pgJDBC-specific mapping, registered only for PgArrayValue, with explicit shape validation and cleanup. */
public final class PgArrayTypeHandler implements JdbcTypeHandler {
    // PostgreSQL's array header holds dimension count, NULL flag and element OID; each dimension adds length and lower bound.
    private static final int HEADER_BYTES = 3 * Integer.BYTES;
    private static final int DIMENSION_BYTES = 2 * Integer.BYTES;

    @Override
    public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
        Object raw = result.getObject(property.getJdbcIndex());
        if (raw == null) return null;
        if (!(raw instanceof Array array)) throw new SQLException("Expected a JDBC Array for " + property.getPropertyType().getTypeName());
        PgArrayValue<?> value;
        try {
            requireSupportedShape(array);
            value = read(array, PgArrayType.fromJdbcName(array.getBaseTypeName()));
        } catch (SQLException | RuntimeException | Error failure) {
            try { array.free(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        array.free();
        return value;
    }

    private static <E> PgArrayValue<E> read(Array array, PgArrayType<E> type) throws SQLException {
        var values = new ArrayList<E>();
        try (var elements = array.getResultSet()) {
            while (elements.next()) values.add(elements.getObject(2, type.elementType()));
        }
        return type.value(values);
    }

    private static void requireSupportedShape(Array array) throws SQLException {
        if (!(array instanceof PgArray pg)) throw new SQLException("PostgreSQL array mapping requires pgJDBC to validate dimensions and lower bounds");
        byte[] bytes = pg.toBytes();
        if (bytes != null) {
            if (bytes.length < HEADER_BYTES) throw new SQLException("Truncated PostgreSQL binary array header");
            var header = ByteBuffer.wrap(bytes);
            int dimensions = header.getInt(0);
            if (dimensions == 0) return;
            if (dimensions != 1) throw new SQLException("Only one-dimensional PostgreSQL arrays are supported; found " + dimensions);
            if (bytes.length < HEADER_BYTES + DIMENSION_BYTES) throw new SQLException("Truncated PostgreSQL binary array dimension");
            requireDefaultLowerBound(header.getInt(HEADER_BYTES + Integer.BYTES));
            return;
        }
        String text = pg.toString();
        if (text == null) throw new SQLException("PostgreSQL array has no readable representation");
        if (text.startsWith("[")) {
            int colon = text.indexOf(':');
            int end = text.indexOf(']');
            if (colon < 0 || end < colon || end + 1 >= text.length() || text.charAt(end + 1) != '=') {
                throw new SQLException("Only one-dimensional PostgreSQL arrays are supported");
            }
            try { requireDefaultLowerBound(Integer.parseInt(text.substring(1, colon))); }
            catch (NumberFormatException failure) { throw new SQLException("Invalid PostgreSQL array lower bound", failure); }
            text = text.substring(end + 2);
        }
        // getArray()/getResultSet() discard bounds, and binary PgArray.toString() also discards them.
        if (text.startsWith("{{")) throw new SQLException("Only one-dimensional PostgreSQL arrays are supported");
    }

    private static void requireDefaultLowerBound(int lowerBound) throws SQLException {
        if (lowerBound != 1) throw new SQLException("PostgreSQL array lower bound " + lowerBound + " cannot be represented by PgArrayValue; expected 1");
    }

    @Override
    public void setParameter(EasyParameter parameter) throws SQLException {
        var value = (PgArrayValue<?>) parameter.getValue();
        Object[] elements = value.elements().stream().map(value.type()::jdbcElement).toArray();
        Array array = parameter.getPs().getConnection().createArrayOf(value.type().elementSqlName(), elements);
        try {
            // pgJDBC copies the encoded bytes/text in setArray, before addBatch/execute; freeing is safe here.
            parameter.getPs().setArray(parameter.getIndex(), array);
        } catch (SQLException | RuntimeException | Error failure) {
            try { array.free(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        array.free();
    }

}
