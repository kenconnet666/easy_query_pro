package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.LogicDelete;
import com.easy.query.core.annotation.Navigate;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.Version;
import com.easy.query.core.basic.extension.version.VersionLongStrategy;
import com.easy.query.core.enums.RelationTypeEnum;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.BusinessAccountProxy;

import java.util.List;

@Table("eqp_account")
@EntityProxy
public class BusinessAccount implements ProxyEntityAvailable<BusinessAccount, BusinessAccountProxy> {
    @Column(primaryKey = true)
    private Integer id;
    private Integer tenantId;
    private String name;
    @Version(strategy = VersionLongStrategy.class)
    private Long version;
    @LogicDelete
    private Boolean deleted;
    @Navigate(value = RelationTypeEnum.OneToMany, selfProperty = "id", targetProperty = "accountId")
    private List<BusinessOrder> orders;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public List<BusinessOrder> getOrders() {
        return orders;
    }

    public void setOrders(List<BusinessOrder> orders) {
        this.orders = orders;
    }
}
