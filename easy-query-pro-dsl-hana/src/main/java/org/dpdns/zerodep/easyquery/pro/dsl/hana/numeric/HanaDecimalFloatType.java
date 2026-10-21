package org.dpdns.zerodep.easyquery.pro.dsl.hana.numeric;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;

import java.math.BigDecimal;

/**
 * Finite decimal floating-point parameters that must survive storage without rounding or underflow.
 */
public enum HanaDecimalFloatType implements NumericType<BigDecimal> {
    SMALLDECIMAL(16, 385, 398),
    DECIMAL(34, 6145, 6176);

    private final int precision;
    private final int maxIntegerDigits;
    private final int maxScale;

    HanaDecimalFloatType(int precision, int maxIntegerDigits, int maxScale) {
        this.precision = precision;
        this.maxIntegerDigits = maxIntegerDigits;
        this.maxScale = maxScale;
    }

    @Override
    public Class<BigDecimal> javaType() {
        return BigDecimal.class;
    }

    @Override
    public String ddlType(SqlDialect dialect) {
        if (dialect != SqlDialect.HANA) throw new IllegalArgumentException("HANA decimal floating types require HANA");
        return name();
    }

    @Override
    public String castTemplate(SqlDialect dialect) {
        return "CAST({0} AS " + ddlType(dialect) + ")";
    }

    @Override
    public BigDecimal prepareValue(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() == 0) return BigDecimal.ZERO;
        // Check the exponent before stripping zeros: BigDecimal can underflow its own int scale.
        if ((long) value.precision() - value.scale() > maxIntegerDigits) throw outOfRange();
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.precision() > precision || normalized.scale() > maxScale) throw outOfRange();
        return normalized;
    }

    private IllegalArgumentException outOfRange() {
        return new IllegalArgumentException("Value is not exactly representable as HANA " + name());
    }
}
