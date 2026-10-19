package org.dpdns.zerodep.easyquery.pro.dsl.type;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class CommonTypes {
    private CommonTypes() {
    }

    public static NumericType<BigDecimal> decimal(int precision, int scale) {
        return new Decimal(precision, scale);
    }

    private record Decimal(int precision, int scale) implements NumericType<BigDecimal> {
        private Decimal {
            // 只取三库共同的声明范围；PG 特殊 scale、MySQL 更大 precision 留给原生类型。
            if (precision < 1 || precision > 38 || scale < 0 || scale > 30 || scale > precision) {
                throw new IllegalArgumentException("Portable DECIMAL requires 1 <= precision <= 38 and 0 <= scale <= min(precision,30)");
            }
        }

        @Override
        public Class<BigDecimal> javaType() {
            return BigDecimal.class;
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            Objects.requireNonNull(dialect, "dialect");
            return "DECIMAL(" + precision + "," + scale + ")";
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            return "CAST({0} AS " + ddlType(dialect) + ")";
        }

        @Override
        public BigDecimal prepareValue(BigDecimal value) {
            if (value == null) return null;
            if (value.signum() == 0) return BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
            long integerDigits = (long) value.precision() - value.scale();
            if (integerDigits > precision - scale || value.stripTrailingZeros().scale() > scale) {
                throw new IllegalArgumentException("Value is not exactly representable as DECIMAL(" + precision + "," + scale + ")");
            }
            // 先去尾零，再限定为目标 scale，避免驱动对极端输入 scale 做无界扩展。
            return value.stripTrailingZeros().setScale(scale, RoundingMode.UNNECESSARY);
        }
    }
}
