package org.dpdns.zerodep.easyquery.pro.dsl.hana.json;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;

import java.util.Locale;

/**
 * JSON text remains NCLOB or NVARCHAR storage; this descriptor does not create a native JSON SQL type.
 */
public final class HanaJsonTextType extends NativeJsonType {
    private static final HanaJsonTextType NCLOB = new HanaJsonTextType(0);
    private final int capacity;

    private HanaJsonTextType(int capacity) {
        super(SqlDialect.HANA, capacity == 0 ? "NCLOB" : "NVARCHAR(" + capacity + ")");
        this.capacity = capacity;
    }

    public static HanaJsonTextType nclob() {
        return NCLOB;
    }

    public static HanaJsonTextType nvarchar(int utf16Capacity) {
        if (utf16Capacity < 1 || utf16Capacity > 5000)
            throw new IllegalArgumentException("JSON NVARCHAR storage requires 1..5000 UTF-16 units");
        return new HanaJsonTextType(utf16Capacity);
    }

    public boolean usesLobStorage() {
        return capacity == 0;
    }

    @Override
    public String castTemplate(SqlDialect dialect) {
        requireDialect(dialect);
        return usesLobStorage() ? "TO_NCLOB({0})" : super.castTemplate(dialect);
    }

    @Override
    public String prepareValue(String value) {
        String checked = super.prepareValue(value);
        if (checked != null && capacity != 0 && checked.length() > capacity)
            throw new IllegalArgumentException("JSON text exceeds " + capacity + " UTF-16 units");
        return checked;
    }

    @Override
    public boolean matchesDeclaration(String declaration) {
        if (usesLobStorage()) return super.matchesDeclaration(declaration);
        return declaration.toUpperCase(Locale.ROOT).replaceAll("\\s+", "").equals(sqlName());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HanaJsonTextType type && capacity == type.capacity;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(capacity);
    }
}
