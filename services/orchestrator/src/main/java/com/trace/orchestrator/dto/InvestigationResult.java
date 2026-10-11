package com.trace.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Response of {@code POST /internal/v1/investigate} — mirrors
 * {@code InvestigationResult} in {@code contracts/openapi/internal-api.yaml}.
 *
 * <p>Only {@code root_cause.root_cause_label} and {@code root_cause.confidence}
 * are persisted for now (they have columns on {@code exceptions}).
 * {@code findings}, {@code recommendation} and {@code agent_trace} are
 * deserialized so the contract isn't silently narrowed, but have nowhere to
 * live until the audit-trail work (TASK-14) adds the tables.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InvestigationResult(
        @JsonProperty("investigation_id") String investigationId,
        @JsonProperty("status") String status,
        @JsonProperty("findings") Map<String, Object> findings,
        @JsonProperty("root_cause") RootCause rootCause,
        @JsonProperty("recommendation") Map<String, Object> recommendation,
        @JsonProperty("agent_trace") List<Map<String, Object>> agentTrace) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootCause(
            @JsonProperty("root_cause_label") String rootCauseLabel,
            @JsonProperty("confidence") Integer confidence,
            @JsonProperty("evidence") List<String> evidence) {
    }
}
