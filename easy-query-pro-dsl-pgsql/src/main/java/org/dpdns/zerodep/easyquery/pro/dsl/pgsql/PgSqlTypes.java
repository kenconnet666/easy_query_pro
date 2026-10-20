package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.DecimalValues;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayType;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgIntervalType;

import java.math.BigDecimal;

public final class PgSqlTypes {
    private static final Json JSON = new Json();
    private static final Jsonb JSONB = new Jsonb();

    private PgSqlTypes() {
    }

    public static Json json() {
        return JSON;
    }

    public static NumericType<BigDecimal> numeric(int precision, int scale) {
        return new Numeric(precision, scale);
    }

    /**
     * Finite values only; BigDecimal cannot represent PostgreSQL NaN or infinities.
     */
    public static NumericType<BigDecimal> numeric() {
        return UnconstrainedNumeric.INSTANCE;
    }

    private record Numeric(int precision, int scale) implements NumericType<BigDecimal> {
        private Numeric {
            if (precision < 1 || precision > 1000 || scale < -1000 || scale > 1000) {
                throw new IllegalArgumentException("PostgreSQL NUMERIC requires 1 <= precision <= 1000 and -1000 <= scale <= 1000");
            }
        }

        @Override
        public Class<BigDecimal> javaType() {
            return BigDecimal.class;
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            requireNumericDialect(dialect);
            return "NUMERIC(" + precision + "," + scale + ")";
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            return "CAST({0} AS " + ddlType(dialect) + ")";
        }

        @Override
        public BigDecimal prepareValue(BigDecimal value) {
            return DecimalValues.prepare(value, precision, scale);
        }
    }

    private enum UnconstrainedNumeric implements NumericType<BigDecimal> {
        INSTANCE;

        @Override
        public Class<BigDecimal> javaType() {
            return BigDecimal.class;
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            requireNumericDialect(dialect);
            return "NUMERIC";
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            return "CAST({0} AS " + ddlType(dialect) + ")";
        }

        @Override
        public BigDecimal prepareValue(BigDecimal value) {
            if (value == null) return null;
            if (value.signum() == 0) return BigDecimal.ZERO;
            if ((long) value.precision() - value.scale() > 131072) {
                throw new IllegalArgumentException("PostgreSQL NUMERIC exceeds 131072 integer digits");
            }
            var normalized = value.stripTrailingZeros();
            if (normalized.scale() > 16383)
                throw new IllegalArgumentException("PostgreSQL NUMERIC exceeds 16383 fractional digits");
            return normalized;
        }
    }

    private static void requireNumericDialect(SqlDialect dialect) {
        if (dialect != SqlDialect.POSTGRESQL)
            throw new IllegalArgumentException("PostgreSQL NUMERIC requires PostgreSQL");
    }

    public static Jsonb jsonb() {
        return JSONB;
    }

    public static <E> PgArrayType<E> array(Class<E> elementType) {
        return PgArrayType.of(elementType);
    }

    public static PgIntervalType interval() {
        return PgIntervalType.INSTANCE;
    }

    public static final class Json extends NativeJsonType {
        private Json() {
            super(SqlDialect.POSTGRESQL, "JSON");
        }
    }

    public static final class Jsonb extends NativeJsonType {
        private Jsonb() {
            super(SqlDialect.POSTGRESQL, "JSONB");
        }
    }
}
