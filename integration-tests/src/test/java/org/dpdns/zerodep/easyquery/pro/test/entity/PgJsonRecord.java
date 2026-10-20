package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonbColumnConverter;
import com.easy.query.core.basic.jdbc.types.handler.StringTypeHandler;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgJsonRecordProxy;

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
    @Column(dbType = "JSONB", conversion = JsonPayload.Converter.class, sqlConversion = PgJsonbColumnConverter.class, typeHandler = StringTypeHandler.class)
    private JsonPayload payload;

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

    public JsonPayload getPayload() {
        return payload;
    }

    public void setPayload(JsonPayload payload) {
        this.payload = payload;
    }

}
