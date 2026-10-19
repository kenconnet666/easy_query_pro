package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestEventProxy;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Table("eqp_event")
@EntityProxy
public class TestEvent implements ProxyEntityAvailable<TestEvent, TestEventProxy> {
    private LocalDateTime occurred;
    private LocalDate eventDate;
    private Integer delta;

    public LocalDateTime getOccurred() {
        return occurred;
    }

    public void setOccurred(LocalDateTime occurred) {
        this.occurred = occurred;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    public Integer getDelta() {
        return delta;
    }

    public void setDelta(Integer delta) {
        this.delta = delta;
    }
}
