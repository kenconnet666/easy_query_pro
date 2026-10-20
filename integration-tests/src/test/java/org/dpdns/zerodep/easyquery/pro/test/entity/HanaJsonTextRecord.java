package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.basic.jdbc.types.handler.StringTypeHandler;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaJsonTextRecordProxy;

@Table("eqp_hana_json_text")
@EntityProxy
public class HanaJsonTextRecord implements ProxyEntityAvailable<HanaJsonTextRecord, HanaJsonTextRecordProxy> {
    @Column(primaryKey = true)
    private Integer id;
    @Column(dbType = "NCLOB")
    private String wide;
    @Column(dbType = "NVARCHAR(1000)")
    private String small;
    @Column(dbType = "NCLOB", conversion = JsonPayload.Converter.class, typeHandler = StringTypeHandler.class)
    private JsonPayload payload;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getWide() {
        return wide;
    }

    public void setWide(String wide) {
        this.wide = wide;
    }

    public String getSmall() {
        return small;
    }

    public void setSmall(String small) {
        this.small = small;
    }

    public JsonPayload getPayload() {
        return payload;
    }

    public void setPayload(JsonPayload payload) {
        this.payload = payload;
    }
}
