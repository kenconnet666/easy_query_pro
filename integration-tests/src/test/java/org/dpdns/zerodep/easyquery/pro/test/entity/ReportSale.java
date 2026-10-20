package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ReportSaleProxy;

import java.math.BigDecimal;
import java.time.LocalDate;

@Table("eqp_report_sale")
@EntityProxy
public class ReportSale implements ProxyEntityAvailable<ReportSale, ReportSaleProxy> {
    @Column(primaryKey = true)
    private Integer id;
    private String region;
    @Column("report_day")
    private LocalDate day;
    private BigDecimal amount;

    public ReportSale() {
    }

    public ReportSale(int id, String region, LocalDate day, String amount) {
        this.id = id;
        this.region = region;
        this.day = day;
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

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
