package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TemporalRecordProxy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Table("eqp_temporal")
@EntityProxy
public class TemporalRecord implements ProxyEntityAvailable<TemporalRecord, TemporalRecordProxy> {
    @Column(primaryKey = true)
    private Integer id;
    private Instant occurred;
    private LocalDate day;
    private LocalDateTime local;
    private LocalTime clock;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Instant getOccurred() {
        return occurred;
    }

    public void setOccurred(Instant occurred) {
        this.occurred = occurred;
    }

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public LocalDateTime getLocal() {
        return local;
    }

    public void setLocal(LocalDateTime local) {
        this.local = local;
    }

    public LocalTime getClock() {
        return clock;
    }

    public void setClock(LocalTime clock) {
        this.clock = clock;
    }
}
