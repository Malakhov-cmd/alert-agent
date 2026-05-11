package com.invest.monitor.state;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "trigger_state")
@IdClass(TriggerStateId.class)
public class TriggerState {

    @Id
    @Column(length = 12)
    private String isin;

    @Id
    @Column(length = 20)
    private String frequency;

    @Column(name = "last_checked_at", nullable = false)
    private LocalDate lastCheckedAt;

    @Column(name = "last_fired_at")
    private LocalDate lastFiredAt;

    protected TriggerState() {}

    public TriggerState(String isin, String frequency, LocalDate lastCheckedAt, LocalDate lastFiredAt) {
        this.isin          = isin;
        this.frequency     = frequency;
        this.lastCheckedAt = lastCheckedAt;
        this.lastFiredAt   = lastFiredAt;
    }

    public String    getIsin()          { return isin; }
    public String    getFrequency()     { return frequency; }
    public LocalDate getLastCheckedAt() { return lastCheckedAt; }
    public LocalDate getLastFiredAt()   { return lastFiredAt; }

    public void setLastCheckedAt(LocalDate v) { this.lastCheckedAt = v; }
    public void setLastFiredAt(LocalDate v)   { this.lastFiredAt   = v; }
}
