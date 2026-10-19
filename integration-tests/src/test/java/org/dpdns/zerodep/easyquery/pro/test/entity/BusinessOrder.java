package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Navigate;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.enums.RelationTypeEnum;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.BusinessOrderProxy;

@Table("eqp_order")
@EntityProxy
public class BusinessOrder implements ProxyEntityAvailable<BusinessOrder, BusinessOrderProxy> {
    @Column(primaryKey = true)
    private Integer id;
    @Column(primaryKey = true)
    private Integer tenantId;
    private Integer accountId;
    private String name;
    @Navigate(value = RelationTypeEnum.ManyToOne, selfProperty = "accountId", targetProperty = "id")
    private BusinessAccount account;
    @Navigate(value = RelationTypeEnum.ManyToOne, selfProperty = "tenantId", targetProperty = "tenantId")
    private BusinessAccount tenantAccount;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer tenantId) {
        this.tenantId = tenantId;
    }

    public Integer getAccountId() {
        return accountId;
    }

    public void setAccountId(Integer accountId) {
        this.accountId = accountId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BusinessAccount getAccount() {
        return account;
    }

    public void setAccount(BusinessAccount account) {
        this.account = account;
    }

    public BusinessAccount getTenantAccount() {
        return tenantAccount;
    }

    public void setTenantAccount(BusinessAccount tenantAccount) {
        this.tenantAccount = tenantAccount;
    }
}
