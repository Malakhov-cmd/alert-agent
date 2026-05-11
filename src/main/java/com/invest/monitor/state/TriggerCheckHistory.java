package com.invest.monitor.state;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "trigger_check_history")
public class TriggerCheckHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 12, nullable = false)
    private String isin;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(name = "condition_text", nullable = false, columnDefinition = "TEXT")
    private String conditionText;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @Column(name = "run_mode", length = 20, nullable = false)
    private String runMode;

    @Column(nullable = false)
    private boolean fired;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(length = 10)
    private String confidence;

    protected TriggerCheckHistory() {}

    public TriggerCheckHistory(String isin, String name, String conditionText,
                                LocalDateTime checkedAt, String runMode,
                                boolean fired, String summary, String details, String confidence) {
        this.isin          = isin;
        this.name          = name;
        this.conditionText = conditionText;
        this.checkedAt     = checkedAt;
        this.runMode       = runMode;
        this.fired         = fired;
        this.summary       = summary;
        this.details       = details;
        this.confidence    = confidence;
    }
}
