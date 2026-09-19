package com.trace.orchestrator.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The deviation-percentage thresholds {@link com.trace.orchestrator.service.ExceptionClassifier}
 * uses to assign severity, configured under {@code trace.classification.severity-bands}
 * in {@code application.yml} (TASK-02 AC: LOW &lt;5%, MEDIUM 5-15%, HIGH
 * 15-30%, CRITICAL &gt;30%). Each {@code *MaxPct} is the exclusive upper
 * bound of its band — a deviation exactly at the threshold falls into the
 * next (more severe) band, keeping the four bands non-overlapping.
 */
@Component
@ConfigurationProperties(prefix = "trace.classification.severity-bands")
public class SeverityBandsProperties {

    private BigDecimal lowMaxPct = BigDecimal.valueOf(5);
    private BigDecimal mediumMaxPct = BigDecimal.valueOf(15);
    private BigDecimal highMaxPct = BigDecimal.valueOf(30);

    public BigDecimal getLowMaxPct() {
        return lowMaxPct;
    }

    public void setLowMaxPct(BigDecimal lowMaxPct) {
        this.lowMaxPct = lowMaxPct;
    }

    public BigDecimal getMediumMaxPct() {
        return mediumMaxPct;
    }

    public void setMediumMaxPct(BigDecimal mediumMaxPct) {
        this.mediumMaxPct = mediumMaxPct;
    }

    public BigDecimal getHighMaxPct() {
        return highMaxPct;
    }

    public void setHighMaxPct(BigDecimal highMaxPct) {
        this.highMaxPct = highMaxPct;
    }
}
