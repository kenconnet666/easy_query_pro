package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;

/**
 * 导航额外列只存在于导航结果，不能加到普通生成器 DTO 中令其选择不存在的列。
 */
@EntityProxy
public class HanaHierarchyNavigationRow extends HanaHierarchyRow {
    @Column("START_RANK")
    private Long startRank;
    @Column("HIERARCHY_DISTANCE")
    private Integer distance;

    public Long getStartRank() {
        return startRank;
    }

    public void setStartRank(Long startRank) {
        this.startRank = startRank;
    }

    public Integer getDistance() {
        return distance;
    }

    public void setDistance(Integer distance) {
        this.distance = distance;
    }
}
