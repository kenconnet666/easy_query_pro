package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.BigDecimalTypeHandler;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Keeps decimal floating-point exponents out of ngdbc's fixed-point parameter encoder.
 */
public final class HanaBigDecimalTypeHandler extends BigDecimalTypeHandler {
    @Override
    public void setParameter(EasyParameter parameter) throws SQLException {
        var value = (BigDecimal) parameter.getValue();
        if (value != null && value.signum() == 0) value = BigDecimal.ZERO;
        if (value != null && (value.scale() < 0 || value.scale() > 6176 || (long) value.precision() - value.scale() > 38)) {
            // ngdbc 2.30 expands negative scales and can wrap exponents below the decimal128 minimum.
            // Scientific text lets the server apply the declared target's conversion, including underflow.
            parameter.getPs().setString(parameter.getIndex(), value.toString());
        } else {
            parameter.getPs().setBigDecimal(parameter.getIndex(), value);
        }
    }
}
