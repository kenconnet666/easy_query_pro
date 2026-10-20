package example.easyquery;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import example.easyquery.proxy.ProductProxy;

import java.math.BigDecimal;

@EntityProxy
@Table("consumer_product")
public class Product implements ProxyEntityAvailable<Product, ProductProxy> {
    @Column(primaryKey = true)
    private Integer id;
    private String name;
    private BigDecimal amount;

    public Product() {
    }

    public Product(Integer id, String name, BigDecimal amount) {
        this.id = id;
        this.name = name;
        this.amount = amount;
    }

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
