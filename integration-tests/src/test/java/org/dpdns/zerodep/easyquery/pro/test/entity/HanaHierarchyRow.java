package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;

/**
 * 原生属性使用实际 SQL 类型；业务属性保持包装类型，允许孤儿循环生成的 NULL 行。
 */
@EntityProxy
public class HanaHierarchyRow extends HanaHierarchyEdge {
    @Column("HIERARCHY_RANK")
    private Long rank;
    @Column("HIERARCHY_TREE_SIZE")
    private Long treeSize;
    @Column("HIERARCHY_PARENT_RANK")
    private Long parentRank;
    @Column("HIERARCHY_ROOT_RANK")
    private Long rootRank;
    @Column("HIERARCHY_LEVEL")
    private Integer level;
    @Column("HIERARCHY_IS_CYCLE")
    private Short cycle;
    @Column("HIERARCHY_IS_ORPHAN")
    private Short orphan;

    public Long getRank() {
        return rank;
    }

    public void setRank(Long rank) {
        this.rank = rank;
    }

    public Long getTreeSize() {
        return treeSize;
    }

    public void setTreeSize(Long treeSize) {
        this.treeSize = treeSize;
    }

    public Long getParentRank() {
        return parentRank;
    }

    public void setParentRank(Long parentRank) {
        this.parentRank = parentRank;
    }

    public Long getRootRank() {
        return rootRank;
    }

    public void setRootRank(Long rootRank) {
        this.rootRank = rootRank;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Short getCycle() {
        return cycle;
    }

    public void setCycle(Short cycle) {
        this.cycle = cycle;
    }

    public Short getOrphan() {
        return orphan;
    }

    public void setOrphan(Short orphan) {
        this.orphan = orphan;
    }
}
