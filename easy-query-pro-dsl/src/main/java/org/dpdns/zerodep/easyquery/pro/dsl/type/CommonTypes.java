package org.dpdns.zerodep.easyquery.pro.dsl.type;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.DecimalValues;

import java.math.BigDecimal;
import java.util.Objects;

public final class CommonTypes {
    private CommonTypes() {
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

    private record UnicodeText(int maxUtf16Length) implements TextType {
        private UnicodeText {
            if (maxUtf16Length < 1 || maxUtf16Length > 5000) {
                throw new IllegalArgumentException("Portable bounded Unicode text requires 1 <= maxUtf16Length <= 5000");
            }
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            return switch (Objects.requireNonNull(dialect, "dialect")) {
                case MYSQL -> "VARCHAR(" + maxUtf16Length + ") CHARACTER SET utf8mb4";
                case POSTGRESQL -> "VARCHAR(" + maxUtf16Length + ")";
                case HANA -> "NVARCHAR(" + maxUtf16Length + ")";
            };
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
            return DecimalValues.prepare(value, precision, scale);
        }
    }
}
