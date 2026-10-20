package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTextSearchTypeHandlers;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsQuery;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsVector;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgSearchDocumentProxy;

@EntityProxy
public class PgSearchDocument extends SearchDocument implements ProxyEntityAvailable<PgSearchDocument, PgSearchDocumentProxy> {
    @Column(dbType = "tsvector", typeHandler = PgTextSearchTypeHandlers.Vector.class)
    private PgTsVector document;
    @Column(dbType = "tsquery", typeHandler = PgTextSearchTypeHandlers.Query.class)
    private PgTsQuery condition;

    public PgTsVector getDocument() {
        return document;
    }

    public void setDocument(PgTsVector document) {
        this.document = document;
    }

    public PgTsQuery getCondition() {
        return condition;
    }

    public void setCondition(PgTsQuery condition) {
        this.condition = condition;
    }
}
