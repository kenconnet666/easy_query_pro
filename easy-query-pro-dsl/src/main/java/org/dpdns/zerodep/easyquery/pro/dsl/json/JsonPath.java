package org.dpdns.zerodep.easyquery.pro.dsl.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable, single-value navigation. Keys are literal names; indexes are zero-based.
 */
public final class JsonPath {
    private static final JsonPath ROOT = new JsonPath(List.of());
    private final List<Segment> segments;

    private JsonPath(List<Segment> segments) {
        this.segments = List.copyOf(segments);
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
        var copy = new ArrayList<>(segments);
        copy.add(segment);
        return new JsonPath(copy);
    }

    /**
     * SQL/JSON dot-quoted notation. Bind this value as data; it is not an SQL literal.
     */
    public String toSqlJsonPath() {
        var result = new StringBuilder("$");
        for (var segment : segments) {
            if (segment instanceof Key key) {
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
        return other instanceof JsonPath path && segments.equals(path.segments);
    }

    public sealed interface Segment permits Key, Index {
    }

    public record Key(String name) implements Segment {
        public Key {
            Objects.requireNonNull(name, "name");
            if (name.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(name)) {
                throw new IllegalArgumentException("JSON keys must be valid Unicode without NUL");
            }
        }
    }

    public record Index(int value) implements Segment {
        public Index {
            if (value < 0) throw new IllegalArgumentException("A portable JSON index must be nonnegative");
        }
    }
}
