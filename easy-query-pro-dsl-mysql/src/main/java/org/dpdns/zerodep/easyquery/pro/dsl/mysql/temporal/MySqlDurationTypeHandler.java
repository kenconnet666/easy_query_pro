package org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal;

import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.ObjectTypeHandler;
import com.mysql.cj.jdbc.ServerPreparedStatement;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;

/**
 * Uses Connector/J's typed Duration reader and chooses the correct binding for each preparation mode.
 */
public final class MySqlDurationTypeHandler extends ObjectTypeHandler {
    private static final MySqlDurationType NATIVE_CAPACITY = new MySqlDurationType(6);

    @Override
    public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
        return result.getObject(property.getJdbcIndex(), Duration.class);
    }

    @Override
    public void setParameter(EasyParameter parameter) throws SQLException {
        Duration value = NATIVE_CAPACITY.prepareValue((Duration) parameter.getValue());
        // Binary TIME parameters preserve server-side type inference; wrappers expose the real statement through JDBC.
        if (value == null || parameter.getPs().isWrapperFor(ServerPreparedStatement.class)) {
            super.setParameter(parameter);
            return;
        }
        // Connector/J's client encoder leaves a trailing S for seconds-only durations, which strict INSERT rejects.
        var magnitude = value.abs();
        String time = String.format(Locale.ROOT, "%s%02d:%02d:%02d.%06d", value.isNegative() ? "-" : "",
                magnitude.toHours(), magnitude.toMinutesPart(), magnitude.toSecondsPart(), magnitude.getNano() / 1000);
        parameter.getPs().setString(parameter.getIndex(), time);
    }
}
