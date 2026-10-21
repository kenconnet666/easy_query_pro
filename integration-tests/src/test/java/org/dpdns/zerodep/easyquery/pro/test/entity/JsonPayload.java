package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.basic.extension.conversion.ValueConverter;
import com.easy.query.core.metadata.ColumnMetadata;
import com.mysql.cj.xdevapi.JsonNumber;
import com.mysql.cj.xdevapi.JsonParser;
import com.mysql.cj.xdevapi.JsonString;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Objects;
import com.mysql.cj.xdevapi.DbDoc;

public final class JsonPayload {
    private final String name;
    private final int count;

    public JsonPayload(String name, int count) {
        this.name = name;
        this.count = count;
    }
    public static final class Converter implements ValueConverter<JsonPayload, String> {
        private final AtomicInteger writes = new AtomicInteger();

        public int writes() {
            return writes.get();
        }

        @Override
        public String serialize(JsonPayload value, @NotNull ColumnMetadata column) {
            if (value == null) return null;
            writes.incrementAndGet();
            // Reuse the test classpath codec without imposing a production serialization library.
            DbDoc doc = JsonParser.parseDoc("{}");
            doc.put("name", new JsonString().setValue(value.name()));
            doc.put("count", new JsonNumber().setValue(Integer.toString(value.count())));
            return doc.toString();
        }

        @Override
        public JsonPayload deserialize(String value, @NotNull ColumnMetadata column) {
            if (value == null || value.equals("null")) return null;
            DbDoc doc = JsonParser.parseDoc(value);
            return new JsonPayload(((JsonString) doc.get("name")).getString(), ((JsonNumber) doc.get("count")).getInteger());
        }
    }

    public String name() {
        return name;
    }

    public int count() {
        return count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JsonPayload)) return false;
        JsonPayload other = (JsonPayload) o;
        return Objects.equals(name, other.name)
                && count == other.count;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, count);
    }

    @Override
    public String toString() {
        return "JsonPayload[name=" + name + ", count=" + count + "]";
    }
}
