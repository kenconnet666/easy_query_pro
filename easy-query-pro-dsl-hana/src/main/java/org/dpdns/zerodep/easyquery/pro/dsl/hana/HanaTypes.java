package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.DecimalValues;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NumericType;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.numeric.HanaDecimalFloatType;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.text.HanaCharacterType;
import org.dpdns.zerodep.easyquery.pro.dsl.type.TextType;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CastTarget;
import org.dpdns.zerodep.easyquery.pro.dsl.type.StorageType;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.json.HanaJsonTextType;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * HANA 类型目录，明确区分存储类型、可 CAST 目标和只声明操作语义的 view。
 *
 * <p>Java 类型相同不意味着 SQL 能力相同：SHORTTEXT/TEXT 是存储或索引选择，不能自动当作 CAST 目标；
 * JSON 文本存储在 NVARCHAR/NCLOB 中，也不会因此变成 JSONB。VARCHAR/CLOB 的 ASCII 约束是本包明确的
 * 参数契约，Unicode 使用 NVARCHAR/NCLOB；相关文本容量按 UTF-16 单元计算，代理对通常占两个单位。
 *
 * <p>typed value 拒绝不能精确表示的参数，nativeCast 保留数据库的原生转换行为。
 * 定点 DECIMAL 与浮点 DECIMAL/SMALLDECIMAL 的精度及指数规则分别建模，不能互相替代。
 */
public final class HanaTypes {
    private static final CharacterLob CLOB = new CharacterLob("CLOB");
    private static final CharacterLob NCLOB = new CharacterLob("NCLOB");
    private static final TextLob TEXT = new TextLob("TEXT");
    private static final BinaryLob BLOB = new BinaryLob();

    private HanaTypes() {
    }

    public static TextType varchar(int asciiCapacity) {
        return new HanaCharacterType(HanaCharacterType.Kind.VARCHAR, asciiCapacity);
    }

    public static TextType nvarchar(int utf16Capacity) {
        return new HanaCharacterType(HanaCharacterType.Kind.NVARCHAR, utf16Capacity);
    }

    /**
     * typed value 防止自动补齐改变原值；需要 ALPHANUM 原生补齐语义时使用显式 nativeCast。
     */
    public static TextType alphanum(int utf16Capacity) {
        return new HanaCharacterType(HanaCharacterType.Kind.ALPHANUM, utf16Capacity);
    }

    /**
     * 只开放 SHORTTEXT 存储声明，不暴露当前数据库不存在的 SHORTTEXT CAST。
     */
    public static StorageType<String> shortText(int utf16Capacity) {
        return new ShortText(utf16Capacity);
    }

    private static final class ShortText implements StorageType<String> {
        private final int capacity;

        private ShortText(int capacity) {
            if (capacity < 1 || capacity > 5000)
                throw new IllegalArgumentException("HANA SHORTTEXT requires 1 <= capacity <= 5000");
            this.capacity = capacity;
        }

        @Override
        public Class<String> javaType() {
            return String.class;
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            if (dialect != SqlDialect.HANA)
                throw new IllegalArgumentException("SHORTTEXT requires HANA COLUMN TABLE storage");
            return "SHORTTEXT(" + capacity + ")";
        }

        public int capacity() {
            return capacity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ShortText)) return false;
            ShortText other = (ShortText) o;
            return capacity == other.capacity;
        }

        @Override
        public int hashCode() {
            return Objects.hash(capacity);
        }

        @Override
        public String toString() {
            return "ShortText[capacity=" + capacity + "]";
        }
    }

    /**
     * HANA TINYINT 的 0..255 使用 Short 承载，避免与 Java 有符号 byte 的值域混淆。
     */
    public static NumericType<Short> tinyint() {
        return Tinyint.INSTANCE;
    }

    public static NumericType<BigDecimal> decimal(int precision, int scale) {
        return new Decimal(precision, scale);
    }

    public static NumericType<BigDecimal> floatingDecimal() {
        return HanaDecimalFloatType.DECIMAL;
    }

    public static NumericType<BigDecimal> smallDecimal() {
        return HanaDecimalFloatType.SMALLDECIMAL;
    }

    private enum Tinyint implements NumericType<Short> {
        INSTANCE;

        @Override
        public Class<Short> javaType() {
            return Short.class;
        }

        @Override
        public String ddlType(SqlDialect dialect) {
            if (dialect != SqlDialect.HANA) throw new IllegalArgumentException("HANA TINYINT requires HANA");
            return "TINYINT";
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            return "CAST({0} AS " + ddlType(dialect) + ")";
        }

        @Override
        public Short prepareValue(Short value) {
            if (value != null && (value < 0 || value > 255))
                throw new IllegalArgumentException("HANA TINYINT requires 0 <= value <= 255");
            return value;
        }
    }

    private static final class Decimal implements NumericType<BigDecimal> {
        private final int precision;
        private final int scale;

        private Decimal(int precision, int scale) {
            if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
                throw new IllegalArgumentException("HANA DECIMAL requires 1 <= precision <= 38 and 0 <= scale <= precision");
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
            if (dialect != SqlDialect.HANA) throw new IllegalArgumentException("HANA DECIMAL requires HANA");
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

    public static HanaJsonTextType jsonNclob() {
        return HanaJsonTextType.nclob();
    }

    public static HanaJsonTextType jsonNvarchar(int utf16Capacity) {
        return HanaJsonTextType.nvarchar(utf16Capacity);
    }

    public static CharacterLob clob() {
        return CLOB;
    }

    public static CharacterLob nclob() {
        return NCLOB;
    }

    public static TextLob text() {
        return TEXT;
    }

    public static BinaryLob blob() {
        return BLOB;
    }

    public interface Lob<J> extends StorageType<J> {
        String sqlName();

        @Override
        default String ddlType(SqlDialect dialect) {
            if (dialect != SqlDialect.HANA) throw new IllegalArgumentException("HANA LOB types require HANA");
            return sqlName();
        }
    }

    /**
     * TEXT is a storage/index choice whose returned value is a character LOB; it is not a CAST target.
     */
    public static class TextLob implements Lob<String> {
        private final String name;

        private TextLob(String name) {
            this.name = name;
        }

        @Override
        public final String sqlName() {
            return name;
        }

        @Override
        public final Class<String> javaType() {
            return String.class;
        }
    }

    public static final class CharacterLob extends TextLob implements CastTarget<String> {
        private CharacterLob(String name) {
            super(name);
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            return "TO_" + ddlType(dialect) + "({0})";
        }

        @Override
        public String prepareValue(String value) {
            UnicodeStrings.requirePortable(value, "LOB text");
            if (value != null && sqlName().equals("CLOB") && value.chars().anyMatch(c -> c > 127)) {
                throw new IllegalArgumentException("CLOB parameters require ASCII; use NCLOB for Unicode text");
            }
            return value;
        }
    }

    public static final class BinaryLob implements Lob<byte[]>, CastTarget<byte[]> {
        private BinaryLob() {
        }

        @Override
        public String sqlName() {
            return "BLOB";
        }

        @Override
        public Class<byte[]> javaType() {
            return byte[].class;
        }

        @Override
        public String castTemplate(SqlDialect dialect) {
            return "TO_" + ddlType(dialect) + "({0})";
        }

        @Override
        public byte[] prepareValue(byte[] value) {
            return value == null ? null : value.clone();
        }
    }
}
