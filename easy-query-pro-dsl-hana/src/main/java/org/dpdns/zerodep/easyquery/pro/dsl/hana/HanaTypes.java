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

    public static TextType alphanum(int utf16Capacity) {
        return new HanaCharacterType(HanaCharacterType.Kind.ALPHANUM, utf16Capacity);
    }

    public static StorageType<String> shortText(int utf16Capacity) {
        return new ShortText(utf16Capacity);
    }

    private record ShortText(int capacity) implements StorageType<String> {
        private ShortText {
            if (capacity < 1 || capacity > 5000)
                throw new IllegalArgumentException("HANA SHORTTEXT requires 1 <= capacity <= 5000");
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
    }

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

    private record Decimal(int precision, int scale) implements NumericType<BigDecimal> {
        private Decimal {
            if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
                throw new IllegalArgumentException("HANA DECIMAL requires 1 <= precision <= 38 and 0 <= scale <= precision");
            }
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

    public sealed interface Lob<J> extends StorageType<J> permits TextLob, BinaryLob {
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
    public static sealed class TextLob implements Lob<String> permits CharacterLob {
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
