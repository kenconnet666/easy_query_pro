package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.easy.query.core.basic.jdbc.types.handler.FloatTypeHandler;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.BasicNumericRecordProxy;

@Table("basic_numbers")
@EntityProxy
public class BasicNumericRecord implements ProxyEntityAvailable<BasicNumericRecord, BasicNumericRecordProxy> {
    @Column(primaryKey = true)
    private Integer id;
    private Long longValue;
    @Column(typeHandler = FloatTypeHandler.class)
    private Float realValue;
    private Double doubleValue;

    public BasicNumericRecord() {
    }

    public BasicNumericRecord(Integer id, Long longValue, Float realValue, Double doubleValue) {
        this.id = id;
        this.longValue = longValue;
        this.realValue = realValue;
        this.doubleValue = doubleValue;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Long getLongValue() {
        return longValue;
    }

    public void setLongValue(Long longValue) {
        this.longValue = longValue;
    }

    public Float getRealValue() {
        return realValue;
    }

    public void setRealValue(Float realValue) {
        this.realValue = realValue;
    }

    public Double getDoubleValue() {
        return doubleValue;
    }

    public void setDoubleValue(Double doubleValue) {
        this.doubleValue = doubleValue;
    }
}
