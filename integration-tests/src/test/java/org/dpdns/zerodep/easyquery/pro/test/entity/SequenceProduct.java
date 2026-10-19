package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.hana.key.HanaSequenceGenerator;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SequenceProductProxy;

@Table("eqp_sequence_product")
@EntityProxy
public class SequenceProduct implements ProxyEntityAvailable<SequenceProduct, SequenceProductProxy> {
    @Column(primaryKey = true, generatedKey = true, generatedSQLColumnGenerator = ProductSequence.class)
    private Long id;
    private String name;

    public SequenceProduct() {
    }

    public SequenceProduct(String name) {
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

    public static final class ProductSequence extends HanaSequenceGenerator {
        public ProductSequence(String sequence) {
            super(sequence);
        }
    }
}
