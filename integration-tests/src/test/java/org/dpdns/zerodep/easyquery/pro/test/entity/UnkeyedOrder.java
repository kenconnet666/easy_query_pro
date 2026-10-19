package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Navigate;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.enums.RelationTypeEnum;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.UnkeyedOrderProxy;

@Table("eqp_unkeyed_order")
@EntityProxy
public class UnkeyedOrder implements ProxyEntityAvailable<UnkeyedOrder, UnkeyedOrderProxy> {
    private Integer accountId;
    private String name;
    @Navigate(value = RelationTypeEnum.ManyToOne, selfProperty = "accountId", targetProperty = "id")
    private BusinessAccount account;

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
}
