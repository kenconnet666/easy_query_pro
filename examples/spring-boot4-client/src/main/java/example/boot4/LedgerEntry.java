package example.boot4;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import example.boot4.proxy.LedgerEntryProxy;

import java.math.BigDecimal;

@EntityProxy
@Table("consumer_ledger")
public class LedgerEntry implements ProxyEntityAvailable<LedgerEntry, LedgerEntryProxy> {
    @Column(primaryKey = true)
    private Integer id;
    private String label;
    private BigDecimal amount;

    public LedgerEntry() {
    }

    public LedgerEntry(Integer id, String label, BigDecimal amount) {
        this.id = id;
        this.label = label;
        this.amount = amount;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
