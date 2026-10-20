package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.basic.jdbc.types.handler.StringTypeHandler;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.MySqlJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.MySqlJsonRecordProxy;

@Table("eqp_mysql_json")
@EntityProxy
public class MySqlJsonRecord implements ProxyEntityAvailable<MySqlJsonRecord, MySqlJsonRecordProxy> {
    @Column(primaryKey = true)
    private Integer id;
    @Column(dbType = "JSON", sqlConversion = MySqlJsonColumnConverter.class)
    private String document;
    @Column(dbType = "TEXT")
    private String plain;
    @Column(dbType = "JSON", conversion = JsonPayload.Converter.class, sqlConversion = MySqlJsonColumnConverter.class, typeHandler = StringTypeHandler.class)
    private JsonPayload payload;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
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
