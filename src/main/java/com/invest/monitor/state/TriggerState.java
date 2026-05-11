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

    @Column(name = "last_summary", columnDefinition = "TEXT")
    private String lastSummary;

    @Column(name = "last_details", columnDefinition = "TEXT")
    private String lastDetails;

    @Column(name = "last_confidence", length = 10)
    private String lastConfidence;

    protected TriggerState() {}

    public TriggerState(String isin, String frequency, LocalDate lastCheckedAt, LocalDate lastFiredAt,
                        String lastSummary, String lastDetails, String lastConfidence) {
        this.isin           = isin;
        this.frequency      = frequency;
        this.lastCheckedAt  = lastCheckedAt;
        this.lastFiredAt    = lastFiredAt;
        this.lastSummary    = lastSummary;
        this.lastDetails    = lastDetails;
        this.lastConfidence = lastConfidence;
    }

    public String    getIsin()           { return isin; }
    public String    getFrequency()      { return frequency; }
    public LocalDate getLastCheckedAt()  { return lastCheckedAt; }
    public LocalDate getLastFiredAt()    { return lastFiredAt; }
    public String    getLastSummary()    { return lastSummary; }
    public String    getLastDetails()    { return lastDetails; }
    public String    getLastConfidence() { return lastConfidence; }

    public void setLastCheckedAt(LocalDate v)  { this.lastCheckedAt  = v; }
    public void setLastFiredAt(LocalDate v)    { this.lastFiredAt    = v; }
    public void setLastSummary(String v)       { this.lastSummary    = v; }
    public void setLastDetails(String v)       { this.lastDetails    = v; }
    public void setLastConfidence(String v)    { this.lastConfidence = v; }
}
