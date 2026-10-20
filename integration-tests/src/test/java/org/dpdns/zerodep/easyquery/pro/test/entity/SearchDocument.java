package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;

@Table("eqp_search_document")
@EntityProxy
public class SearchDocument {
    @Column(primaryKey = true)
    private Integer id;
    private String title;
    private String body;
    private String unindexed;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getUnindexed() {
        return unindexed;
    }

    public void setUnindexed(String unindexed) {
        this.unindexed = unindexed;
    }
}
