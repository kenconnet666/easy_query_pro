package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.basic.extension.conversion.ValueConverter;
import com.easy.query.core.metadata.ColumnMetadata;
import com.mysql.cj.xdevapi.JsonNumber;
import com.mysql.cj.xdevapi.JsonParser;
import com.mysql.cj.xdevapi.JsonString;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

public record JsonPayload(String name, int count) {
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
            var doc = JsonParser.parseDoc("{}");
            doc.put("name", new JsonString().setValue(value.name()));
            doc.put("count", new JsonNumber().setValue(Integer.toString(value.count())));
            return doc.toString();
        }

        @Override
        public JsonPayload deserialize(String value, @NotNull ColumnMetadata column) {
            if (value == null || value.equals("null")) return null;
            var doc = JsonParser.parseDoc(value);
            return new JsonPayload(((JsonString) doc.get("name")).getString(), ((JsonNumber) doc.get("count")).getInteger());
        }
    }
}
