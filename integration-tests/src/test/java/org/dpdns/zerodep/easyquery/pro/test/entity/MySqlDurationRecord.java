package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.MySqlDurationRecordProxy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Table("eqp_mysql_duration")
@EntityProxy
public class MySqlDurationRecord implements ProxyEntityAvailable<MySqlDurationRecord, MySqlDurationRecordProxy> {
    @Column(primaryKey = true)
    private Integer id;
    @Column(dbType = "TIME(6)")
    private Duration elapsed;
    @Column(dbType = "TIME")
    private LocalTime clock;
    @Column(dbType = "DATETIME(6)")
    private LocalDateTime started;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Duration getElapsed() {
        return elapsed;
    }

    public void setElapsed(Duration elapsed) {
        this.elapsed = elapsed;
    }

    public LocalTime getClock() {
        return clock;
    }

    public void setClock(LocalTime clock) {
        this.clock = clock;
    }

    public LocalDateTime getStarted() {
        return started;
    }

    public void setStarted(LocalDateTime started) {
        this.started = started;
    }
}
