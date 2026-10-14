package com.trace.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the automatic investigation trigger (US-01), configured under
 * {@code trace.investigation}.
 *
 * <p>{@code sweepGrace} keeps the sweeper from racing the after-commit
 * trigger that fires within milliseconds of ingestion. {@code staleAfter}
 * must comfortably exceed the Engine call budget (8s timeout, retries
 * included) so a healthy in-flight investigation is never declared dead.
 */
@Component
@ConfigurationProperties(prefix = "trace.investigation")
public class InvestigationProperties {

    /** Below this confidence (0-100) the exception goes to NEEDS_HUMAN_REVIEW (US-03). */
    private int confidenceThreshold = 70;

    private int executorCoreSize = 2;
    private int executorMaxSize = 4;
    private int executorQueueCapacity = 50;

    private Duration sweepInterval = Duration.ofSeconds(30);
    private Duration sweepGrace = Duration.ofSeconds(30);
    private Duration staleAfter = Duration.ofMinutes(2);
    private int sweepBatchSize = 25;

    public int getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(int confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public int getExecutorCoreSize() {
        return executorCoreSize;
    }

    public void setExecutorCoreSize(int executorCoreSize) {
        this.executorCoreSize = executorCoreSize;
    }

    public int getExecutorMaxSize() {
        return executorMaxSize;
    }

    public void setExecutorMaxSize(int executorMaxSize) {
        this.executorMaxSize = executorMaxSize;
    }

    public int getExecutorQueueCapacity() {
        return executorQueueCapacity;
    }

    public void setExecutorQueueCapacity(int executorQueueCapacity) {
        this.executorQueueCapacity = executorQueueCapacity;
    }

    public Duration getSweepInterval() {
        return sweepInterval;
    }

    public void setSweepInterval(Duration sweepInterval) {
        this.sweepInterval = sweepInterval;
    }

    public Duration getSweepGrace() {
        return sweepGrace;
    }

    public void setSweepGrace(Duration sweepGrace) {
        this.sweepGrace = sweepGrace;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    public int getSweepBatchSize() {
        return sweepBatchSize;
    }

    public void setSweepBatchSize(int sweepBatchSize) {
        this.sweepBatchSize = sweepBatchSize;
    }
}
