package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CastTarget;
import org.dpdns.zerodep.easyquery.pro.dsl.type.StorageType;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.json.HanaJsonTextType;

public final class HanaTypes {
    private static final CharacterLob CLOB = new CharacterLob("CLOB");
    private static final CharacterLob NCLOB = new CharacterLob("NCLOB");
    private static final TextLob TEXT = new TextLob("TEXT");
    private static final BinaryLob BLOB = new BinaryLob();

    private HanaTypes() {
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
