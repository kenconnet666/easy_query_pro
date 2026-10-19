package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.GeneratedProductProxy;

@Table("eqp_generated_product")
@EntityProxy
public class GeneratedProduct implements ProxyEntityAvailable<GeneratedProduct, GeneratedProductProxy> {
    @Column(primaryKey = true, generatedKey = true)
    private Long id;
    private String name;

    public GeneratedProduct() {
    }

    public GeneratedProduct(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
