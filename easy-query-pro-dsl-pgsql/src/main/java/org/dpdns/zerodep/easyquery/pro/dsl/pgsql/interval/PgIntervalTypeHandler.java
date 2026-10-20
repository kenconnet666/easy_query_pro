package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

public final class PgIntervalTypeHandler implements JdbcTypeHandler {
    @Override
    public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
        // PGInterval loses sql_standard fields and cannot decode valid intervals above Integer.MAX_VALUE hours.
        String text = result.getString(property.getJdbcIndex());
        if (text != null) return PgIntervalCodec.decode(text);
        if (result.wasNull()) return null;
        // Forced interval binary transfer makes pgJDBC getString return null even when the SQL value is present.
        return PgIntervalCodec.decodeBinary(result.getBytes(property.getJdbcIndex()));
    }

    @Override
    public void setParameter(EasyParameter parameter) throws SQLException {
        var value = new PGobject();
        value.setType("interval");
        value.setValue(PgIntervalCodec.encode((PgInterval) parameter.getValue()));
        parameter.getPs().setObject(parameter.getIndex(), value);
    }
}
