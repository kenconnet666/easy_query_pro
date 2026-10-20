package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import org.dpdns.zerodep.easyquery.pro.dsl.type.StorageType;

import java.math.BigInteger;

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
    public static sealed class Unsigned<N extends Number> implements StorageType<N> permits UnsignedBigint {
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
            var integer = value instanceof BigInteger big ? big : BigInteger.valueOf(value.longValue());
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
