package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportOrderProxy;

import java.math.BigDecimal;

@Table("eqp_report_order")
@EntityProxy
public class ReportOrder implements ProxyEntityAvailable<ReportOrder, ReportOrderProxy> {
    @Column(primaryKey = true)
    private Integer id;
    private String region;
    @Column("customer_id")
    private Integer customerId;
    private String status;
    private Integer quantity;
    private BigDecimal amount;

    public ReportOrder() {
    }

    public ReportOrder(int id, String region, Integer customerId, String status, Integer quantity, String amount) {
        this.id = id;
        this.region = region;
        this.customerId = customerId;
        this.status = status;
        this.quantity = quantity;
        this.amount = amount == null ? null : new BigDecimal(amount);
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Integer getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Integer customerId) {
        this.customerId = customerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
