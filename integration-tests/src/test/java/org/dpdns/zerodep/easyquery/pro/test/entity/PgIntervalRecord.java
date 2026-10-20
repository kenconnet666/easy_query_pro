package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgIntervalRecordProxy;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Table("eqp_pg_interval")
@EntityProxy
public class PgIntervalRecord implements ProxyEntityAvailable<PgIntervalRecord, PgIntervalRecordProxy> {
    @Column(primaryKey = true)
    private Integer id;
    @Column(dbType = "INTERVAL")
    private PgInterval span;
    private LocalDate eventDate;
    private LocalDateTime occurred;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public PgInterval getSpan() {
        return span;
    }

    public void setSpan(PgInterval span) {
        this.span = span;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    public LocalDateTime getOccurred() {
        return occurred;
    }

    public void setOccurred(LocalDateTime occurred) {
        this.occurred = occurred;
    }
}
