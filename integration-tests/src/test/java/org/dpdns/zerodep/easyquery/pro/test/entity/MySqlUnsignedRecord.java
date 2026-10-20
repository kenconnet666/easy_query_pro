package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.MySqlUnsignedRecordProxy;

import java.math.BigInteger;

@Table("eqp_mysql_unsigned")
@EntityProxy
public class MySqlUnsignedRecord implements ProxyEntityAvailable<MySqlUnsignedRecord, MySqlUnsignedRecordProxy> {
    @Column(primaryKey = true)
    private Integer id;
    @Column(dbType = "TINYINT UNSIGNED")
    private Short tinyValue;
    @Column(dbType = "SMALLINT UNSIGNED")
    private Integer smallValue;
    @Column(dbType = "MEDIUMINT UNSIGNED")
    private Integer mediumValue;
    @Column(dbType = "INT UNSIGNED")
    private Long intValue;
    @Column(dbType = "BIGINT UNSIGNED")
    private BigInteger bigValue;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Short getTinyValue() {
        return tinyValue;
    }

    public void setTinyValue(Short tinyValue) {
        this.tinyValue = tinyValue;
    }

    public Integer getSmallValue() {
        return smallValue;
    }

    public void setSmallValue(Integer smallValue) {
        this.smallValue = smallValue;
    }

    public Integer getMediumValue() {
        return mediumValue;
    }

    public void setMediumValue(Integer mediumValue) {
        this.mediumValue = mediumValue;
    }

    public Long getIntValue() {
        return intValue;
    }

    public void setIntValue(Long intValue) {
        this.intValue = intValue;
    }

    public BigInteger getBigValue() {
        return bigValue;
    }

    public void setBigValue(BigInteger bigValue) {
        this.bigValue = bigValue;
    }
}
