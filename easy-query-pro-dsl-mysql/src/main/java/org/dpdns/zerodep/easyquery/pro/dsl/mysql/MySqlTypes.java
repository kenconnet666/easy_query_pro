package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.DecimalValues;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import org.dpdns.zerodep.easyquery.pro.dsl.type.StorageType;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationType;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.Objects;

public final class MySqlTypes {
    private static final Json JSON = new Json();
    private static final Unsigned<Short> UNSIGNED_TINYINT = new Unsigned<>(Short.class, "TINYINT", 8);
    private static final Unsigned<Integer> UNSIGNED_SMALLINT = new Unsigned<>(Integer.class, "SMALLINT", 16);
    private static final Unsigned<Integer> UNSIGNED_MEDIUMINT = new Unsigned<>(Integer.class, "MEDIUMINT", 24);
    private static final Unsigned<Long> UNSIGNED_INT = new Unsigned<>(Long.class, "INT", 32);
    private static final UnsignedBigint UNSIGNED_BIGINT = new UnsignedBigint();

    private MySqlTypes() {
    }

    public static Json json() {
        return JSON;
    }

    public static NumericType<BigDecimal> decimal(int precision, int scale) {
        return new Decimal(precision, scale);
    }

    private static final class Decimal implements NumericType<BigDecimal> {
        private final int precision;
        private final int scale;

        private Decimal(int precision, int scale) {
            if (precision < 1 || precision > 65 || scale < 0 || scale > 30 || scale > precision) {
                throw new IllegalArgumentException("MySQL DECIMAL requires 1 <= precision <= 65 and 0 <= scale <= min(precision,30)");
            }
            this.precision = precision;
            this.scale = scale;
        }

        @Override
        public Class<BigDecimal> javaType() {
            return BigDecimal.class;
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            if (dialect != SqlDialect.MYSQL) throw new IllegalArgumentException("MySQL DECIMAL requires MySQL");
            return "DECIMAL(" + precision + "," + scale + ")";
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            return "CAST({0} AS " + ddlType(dialect) + ")";
        }

        @Override
        public BigDecimal prepareValue(BigDecimal value) {
            return DecimalValues.prepare(value, precision, scale);
        }

        public int precision() {
            return precision;
        }

        public int scale() {
            return scale;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Decimal)) return false;
            Decimal other = (Decimal) o;
            return precision == other.precision
                    && scale == other.scale;
        }

        @Override
        public int hashCode() {
            return Objects.hash(precision, scale);
        }

        @Override
        public String toString() {
            return "Decimal[precision=" + precision + ", scale=" + scale + "]";
        }
    }

    public static MySqlDurationType durationTime(int fractionalPrecision) {
        return new MySqlDurationType(fractionalPrecision);
    }

    public static Unsigned<Short> unsignedTinyint() {
        return UNSIGNED_TINYINT;
    }

    public static Unsigned<Integer> unsignedSmallint() {
        return UNSIGNED_SMALLINT;
    }

    public static Unsigned<Integer> unsignedMediumint() {
        return UNSIGNED_MEDIUMINT;
    }

    public static Unsigned<Long> unsignedInt() {
        return UNSIGNED_INT;
    }

    public static UnsignedBigint unsignedBigint() {
        return UNSIGNED_BIGINT;
    }

    /**
     * Storage and validated parameters for a declared unsigned width; narrower widths are not native CAST targets.
     */
    public static class Unsigned<N extends Number> implements StorageType<N> {
        private final Class<N> javaType;
        private final String name;
        private final BigInteger maximum;

        private Unsigned(Class<N> javaType, String name, int bits) {
            this.javaType = javaType;
            this.name = name + " UNSIGNED";
            maximum = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        }

        @Override
        public final Class<N> javaType() {
            return javaType;
        }

        public final BigInteger maximum() {
            return maximum;
        }

        public final N prepareValue(N value) {
            if (value == null) return null;
            if (!javaType.isInstance(value)) throw new IllegalArgumentException("Expected " + javaType.getTypeName());
            BigInteger integer = value instanceof BigInteger ? ((BigInteger) value) : BigInteger.valueOf(value.longValue());
            if (integer.signum() < 0 || integer.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(name + " requires a value between 0 and " + maximum);
            }
            return value;
        }

        @Override
        public final String ddlType(SqlDialect dialect) {
            if (dialect != SqlDialect.MYSQL) throw new IllegalArgumentException("Unsigned integer types require MySQL");
            return name;
        }
    }

    public static final class UnsignedBigint extends Unsigned<BigInteger> implements NumericType<BigInteger> {
        private UnsignedBigint() {
            super(BigInteger.class, "BIGINT", 64);
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            if (dialect != SqlDialect.MYSQL) throw new IllegalArgumentException("Unsigned integer CAST requires MySQL");
            return "CAST({0} AS UNSIGNED)";
        }
    }

    public static final class Json extends NativeJsonType {
        private Json() {
            super(SqlDialect.MYSQL, "JSON");
        }

        @Override
        public String castTemplate(SqlDialect actual) {
            requireDialect(actual);
            // Client-side preparation with NO_BACKSLASH_ESCAPES can bind strings as binary hex.
            return "CAST(CONVERT({0} USING utf8mb4) AS JSON)";
        }
    }
}
