package org.dpdns.zerodep.easyquery.pro.dsl.type;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.DecimalValues;

import java.math.BigDecimal;
import java.util.Objects;

public final class CommonTypes {
    private CommonTypes() {
    }

    public static NumericType<Long> bigint() {
        return Bigint.INSTANCE;
    }

    /**
     * 有限 IEEE 754 单精度参数；MySQL 明确使用 FLOAT，不受 REAL_AS_FLOAT 模式影响。
     */
    public static NumericType<Float> real() {
        return Real.INSTANCE;
    }

    /**
     * 有限 IEEE 754 双精度参数。负零规范为正零，避免承诺 HANA 不保留的零符号。
     */
    public static NumericType<Double> doublePrecision() {
        return DoublePrecision.INSTANCE;
    }

    public static NumericType<BigDecimal> decimal(int precision, int scale) {
        return new Decimal(precision, scale);
    }

    /**
     * A lossless parameter capacity measured in Java UTF-16 code units, not Unicode code points.
     */
    public static TextType unicodeText(int maxUtf16Length) {
        return new UnicodeText(maxUtf16Length);
    }

    private static final class UnicodeText implements TextType {
        private final int maxUtf16Length;

        private UnicodeText(int maxUtf16Length) {
            if (maxUtf16Length < 1 || maxUtf16Length > 5000) {
                throw new IllegalArgumentException("Portable bounded Unicode text requires 1 <= maxUtf16Length <= 5000");
            }
            this.maxUtf16Length = maxUtf16Length;
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            switch (Objects.requireNonNull(dialect, "dialect")) {
                case MYSQL: return "VARCHAR(" + maxUtf16Length + ") CHARACTER SET utf8mb4";
                case POSTGRESQL: return "VARCHAR(" + maxUtf16Length + ")";
                case HANA: return "NVARCHAR(" + maxUtf16Length + ")";
            }
            throw new IllegalStateException("Unhandled value: " + Objects.requireNonNull(dialect, "dialect"));
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            // MySQL 的 DDL VARCHAR 不是 CAST 目标；字符集也必须明确支持补充平面字符。
            String target = dialect == SqlDialect.MYSQL ? "CHAR(" + maxUtf16Length + ") CHARACTER SET utf8mb4" : ddlType(dialect);
            return "CAST({0} AS " + target + ")";
        }

        @Override
        public String prepareValue(String value) {
            if (value == null) return null;
            if (value.length() > maxUtf16Length)
                throw new IllegalArgumentException("Text exceeds " + maxUtf16Length + " UTF-16 code units");
            return UnicodeStrings.requirePortable(value, "Text");
        }

        public int maxUtf16Length() {
            return maxUtf16Length;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UnicodeText)) return false;
            UnicodeText other = (UnicodeText) o;
            return maxUtf16Length == other.maxUtf16Length;
        }

        @Override
        public int hashCode() {
            return Objects.hash(maxUtf16Length);
        }

        @Override
        public String toString() {
            return "UnicodeText[maxUtf16Length=" + maxUtf16Length + "]";
        }
    }

    private static final class Decimal implements NumericType<BigDecimal> {
        private final int precision;
        private final int scale;

        private Decimal(int precision, int scale) {
            // 只取三库共同的声明范围；PG 特殊 scale、MySQL 更大 precision 留给原生类型。
            if (precision < 1 || precision > 38 || scale < 0 || scale > 30 || scale > precision) {
                throw new IllegalArgumentException("Portable DECIMAL requires 1 <= precision <= 38 and 0 <= scale <= min(precision,30)");
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
            Objects.requireNonNull(dialect, "dialect");
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

    private enum Bigint implements NumericType<Long> {
        INSTANCE;

        @Override
        public Class<Long> javaType() {
            return Long.class;
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            Objects.requireNonNull(dialect, "dialect");
            return "BIGINT";
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            String target = dialect == SqlDialect.MYSQL ? "SIGNED" : ddlType(dialect);
            return "CAST({0} AS " + target + ")";
        }

        @Override
        public Long prepareValue(Long value) {
            return value;
        }
    }

    private enum Real implements NumericType<Float> {
        INSTANCE;

        @Override
        public Class<Float> javaType() {
            return Float.class;
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            switch (Objects.requireNonNull(dialect, "dialect")) {
                case MYSQL: return "FLOAT";
                case POSTGRESQL: case HANA: return "REAL";
            }
            throw new IllegalStateException("Unhandled value: " + Objects.requireNonNull(dialect, "dialect"));
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            return "CAST({0} AS " + ddlType(dialect) + ")";
        }

        @Override
        public Float prepareValue(Float value) {
            if (value == null) return null;
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Portable REAL requires a finite Float");
            return value == 0f ? 0f : value;
        }
    }

    private enum DoublePrecision implements NumericType<Double> {
        INSTANCE;

        @Override
        public Class<Double> javaType() {
            return Double.class;
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            switch (Objects.requireNonNull(dialect, "dialect")) {
                case MYSQL: case HANA: return "DOUBLE";
                case POSTGRESQL: return "DOUBLE PRECISION";
            }
            throw new IllegalStateException("Unhandled value: " + Objects.requireNonNull(dialect, "dialect"));
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            return "CAST({0} AS " + ddlType(dialect) + ")";
        }

        @Override
        public Double prepareValue(Double value) {
            if (value == null) return null;
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Portable DOUBLE requires a finite Double");
            return value == 0d ? 0d : value;
        }
    }
}
