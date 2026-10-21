package org.dpdns.zerodep.easyquery.pro.dsl.hana.text;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;
import org.dpdns.zerodep.easyquery.pro.dsl.type.TextType;
import java.util.Objects;

/**
 * Native character declarations; typed values preserve their exact Java string representation.
 */
public final class HanaCharacterType implements TextType {
    private final Kind kind;
    private final int capacity;

    public enum Kind {VARCHAR, NVARCHAR, ALPHANUM}

    public HanaCharacterType(Kind kind, int capacity) {
        java.util.Objects.requireNonNull(kind, "kind");
        int maximum = kind == Kind.ALPHANUM ? 127 : 5000;
        if (capacity < 1 || capacity > maximum)
            throw new IllegalArgumentException(kind + " requires 1 <= capacity <= " + maximum);
        this.kind = kind;
        this.capacity = capacity;
    }

    @Override
    public String ddlType(SqlDialect dialect) {
        if (dialect != SqlDialect.HANA) throw new IllegalArgumentException("HANA character types require HANA");
        return kind.name() + "(" + capacity + ")";
    }

    @Override
    public String castTemplate(SqlDialect dialect) {
        return "CAST({0} AS " + ddlType(dialect) + ")";
    }

    @Override
    public String prepareValue(String value) {
        if (value == null) return null;
        UnicodeStrings.requirePortable(value, "HANA character value");
        if (value.length() > capacity)
            throw new IllegalArgumentException(kind + " exceeds " + capacity + " UTF-16 code units");
        if (kind == Kind.VARCHAR && value.chars().anyMatch(c -> c > 127)) {
            throw new IllegalArgumentException("HANA VARCHAR parameters require ASCII; use NVARCHAR for Unicode text");
        }
        if (kind == Kind.ALPHANUM && !value.isEmpty() && value.length() < capacity && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Numeric ALPHANUM parameters require their complete zero-padded representation; use nativeCast for padding");
        }
        return value;
    }

    public Kind kind() {
        return kind;
    }

    public int capacity() {
        return capacity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HanaCharacterType)) return false;
        HanaCharacterType other = (HanaCharacterType) o;
        return Objects.equals(kind, other.kind)
                && capacity == other.capacity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, capacity);
    }

    @Override
    public String toString() {
        return "HanaCharacterType[kind=" + kind + ", capacity=" + capacity + "]";
    }
}
