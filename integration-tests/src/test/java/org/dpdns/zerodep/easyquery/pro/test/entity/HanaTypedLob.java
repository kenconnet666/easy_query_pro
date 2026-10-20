package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaTypedLobProxy;

@Table("eqp_typed_lob")
@EntityProxy
public class HanaTypedLob implements ProxyEntityAvailable<HanaTypedLob, HanaTypedLobProxy> {
    @Column(primaryKey = true)
    private Integer id;
    @Column(dbType = "CLOB")
    private String ascii;
    @Column(dbType = "NCLOB")
    private String text;
    @Column(dbType = "TEXT")
    private String searchable;
    @Column(dbType = "BLOB")
    private byte[] data;
    @Column(dbType = "NVARCHAR(100)")
    private String plain;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getAscii() {
        return ascii;
    }

    public void setAscii(String ascii) {
        this.ascii = ascii;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getSearchable() {
        return searchable;
    }

    public void setSearchable(String searchable) {
        this.searchable = searchable;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public String getPlain() {
        return plain;
    }

    public void setPlain(String plain) {
        this.plain = plain;
    }
}
