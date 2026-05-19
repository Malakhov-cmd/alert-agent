package com.invest.monitor.retry;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "check_retry_queue")
public class CheckRetryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String isin;

    @Column(nullable = false, length = 20)
    private String frequency;

    @Column(nullable = false, length = 20)
    private String label;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CheckRetryEntry() {}

    public CheckRetryEntry(String isin, String frequency, String label,
                           int attempt, LocalDateTime scheduledAt) {
        this.isin        = isin;
        this.frequency   = frequency;
        this.label       = label;
        this.attempt     = attempt;
        this.scheduledAt = scheduledAt;
        this.createdAt   = LocalDateTime.now();
    }

    public Long          getId()          { return id; }
    public String        getIsin()        { return isin; }
    public String        getFrequency()   { return frequency; }
    public String        getLabel()       { return label; }
    public int           getAttempt()     { return attempt; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }

    public void reschedule(LocalDateTime newTime) {
        this.attempt++;
        this.scheduledAt = newTime;
    }
}
