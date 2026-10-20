package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaViewRowProxy;

import java.math.BigDecimal;

/**
 * HDI 输出使用带引号的大写列名，不能依赖普通实体的小写命名约定。
 */
@EntityProxy
public class HanaViewRow implements ProxyEntityAvailable<HanaViewRow, HanaViewRowProxy> {
    @Column("ID")
    private Integer id;
    @Column("NAME")
    private String name;
    @Column("AMOUNT")
    private BigDecimal amount;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
