package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;

import java.math.BigDecimal;

/**
 * 邻接表与层级输出共用业务属性；SQL 名称故意不使用 HANA 的 NODE_ID/PARENT_ID 约定。
 */
@Table("eqp_hierarchy_edge")
@EntityProxy
public class HanaHierarchyEdge {
    private Integer id;
    @Column("parent_id")
    private Integer parentId;
    @Column("sort_order")
    private Integer sortOrder;
    private String name;
    private BigDecimal amount;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getParentId() {
        return parentId;
    }

    public void setParentId(Integer parentId) {
        this.parentId = parentId;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
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
