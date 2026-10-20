package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.basic.extension.conversion.ValueConverter;
import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.mysql.cj.xdevapi.JsonNumber;
import com.mysql.cj.xdevapi.JsonParser;
import com.mysql.cj.xdevapi.JsonString;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonbColumnConverter;
import com.easy.query.core.basic.jdbc.types.handler.StringTypeHandler;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgJsonRecordProxy;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

@Table("eqp_pg_json")
@EntityProxy
public class PgJsonRecord implements ProxyEntityAvailable<PgJsonRecord, PgJsonRecordProxy> {
    @Column(primaryKey = true)
    private Integer id;
    @Column(dbType = "JSON", sqlConversion = PgJsonColumnConverter.class)
    private String json;
    @Column(dbType = "JSONB", sqlConversion = PgJsonbColumnConverter.class)
    private String document;
    @Column(dbType = "TEXT")
    private String plain;
    @Column(dbType = "JSONB", conversion = PayloadConverter.class, sqlConversion = PgJsonbColumnConverter.class, typeHandler = StringTypeHandler.class)
    private Payload payload;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getJson() {
        return json;
    }

    public void setJson(String json) {
        this.json = json;
    }

    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
    }

    public String getPlain() {
        return plain;
    }

    public void setPlain(String plain) {
        this.plain = plain;
    }

    public Payload getPayload() {
        return payload;
    }

    public void setPayload(Payload payload) {
        this.payload = payload;
    }

    public record Payload(String name, int count) {
    }

    public static final class PayloadConverter implements ValueConverter<Payload, String> {
        private final AtomicInteger writes = new AtomicInteger();

        public int writes() {
            return writes.get();
        }

        @Override
        public String serialize(Payload value, @NotNull ColumnMetadata column) {
            if (value == null) return null;
            writes.incrementAndGet();
            // 测试复用已在 classpath 的 JSON codec；生产增强包不要求使用这个序列化器。
            var doc = JsonParser.parseDoc("{}");
            doc.put("name", new JsonString().setValue(value.name()));
            doc.put("count", new JsonNumber().setValue(Integer.toString(value.count())));
            return doc.toString();
        }

        @Override
        public Payload deserialize(String value, @NotNull ColumnMetadata column) {
            if (value == null || value.equals("null")) return null;
            var doc = JsonParser.parseDoc(value);
            return new Payload(((JsonString) doc.get("name")).getString(), ((JsonNumber) doc.get("count")).getInteger());
        }
    }
}
