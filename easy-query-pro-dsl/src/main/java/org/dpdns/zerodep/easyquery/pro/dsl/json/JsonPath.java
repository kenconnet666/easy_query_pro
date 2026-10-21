package org.dpdns.zerodep.easyquery.pro.dsl.json;

import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;

/**
 * Immutable, single-value navigation. Keys are literal names; indexes are zero-based.
 */
public final class JsonPath {
    private static final JsonPath ROOT = new JsonPath(ImmutableCollections.listOf());
    private final List<Segment> segments;

    private JsonPath(List<Segment> segments) {
        this.segments = ImmutableCollections.listCopyOf(segments);
    }

    public static JsonPath root() {
        return ROOT;
    }

    public JsonPath key(String name) {
        return append(new Key(name));
    }

    public JsonPath index(int index) {
        return append(new Index(index));
    }

    public List<Segment> segments() {
        return segments;
    }

    private JsonPath append(Segment segment) {
        if (segments.size() >= 100)
            throw new IllegalArgumentException("A portable JSON path supports at most 100 segments");
        ArrayList<JsonPath.Segment> copy = new ArrayList<>(segments);
        copy.add(segment);
        return new JsonPath(copy);
    }

    /**
     * SQL/JSON dot-quoted notation. Bind this value as data; it is not an SQL literal.
     */
    public String toSqlJsonPath() {
        StringBuilder result = new StringBuilder("$");
        for (JsonPath.Segment segment : segments) {
            if (segment instanceof Key) {
                Key key = (Key) segment;
                result.append(".\"");
                for (int i = 0; i < key.name().length(); i++) {
                    char c = key.name().charAt(i);
                    if (c == '\\' || c == '"') result.append('\\').append(c);
                    else if (c < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else result.append(c);
                }
                result.append('"');
            } else {
                result.append('[').append(((Index) segment).value()).append(']');
            }
        }
        return result.toString();
    }

    @Override
    public String toString() {
        return toSqlJsonPath();
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonPath && segments.equals(((JsonPath) other).segments);
    }

    public interface Segment {
    }

    public static final class Key implements Segment {
        private final String name;

        public Key(String name) {
            Objects.requireNonNull(name, "name");
            UnicodeStrings.requirePortable(name, "JSON key");
            this.name = name;
        }

        public String name() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key other = (Key) o;
            return Objects.equals(name, other.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }

        @Override
        public String toString() {
            return "Key[name=" + name + "]";
        }
    }

    public static final class Index implements Segment {
        private final int value;

        public Index(int value) {
            if (value < 0) throw new IllegalArgumentException("A portable JSON index must be nonnegative");
            this.value = value;
        }

        public int value() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Index)) return false;
            Index other = (Index) o;
            return value == other.value;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }

        @Override
        public String toString() {
            return "Index[value=" + value + "]";
        }
    }
}
