package com.trace.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Response body for {@code POST /api/v1/exceptions/create} — must match the
 * {@code Exception} schema in {@code contracts/openapi/client-api.yaml}
 * exactly (TDD §3.1).
 *
 * <p>{@code type}/{@code severity} are always populated on a successful
 * ingestion (TASK-02's classifier runs synchronously before this response is
 * built) — {@code @JsonInclude(NON_NULL)} is kept defensively rather than
 * relied on, in case a future producer of this DTO doesn't classify.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExceptionResponse {

    @JsonProperty("exception_id")
    private String exceptionId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("type")
    private String type;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("links")
    private Links links;

    public ExceptionResponse() {
    }

    public ExceptionResponse(String exceptionId, String status, String type, String severity,
            Instant createdAt, Links links) {
        this.exceptionId = exceptionId;
        this.status = status;
        this.type = type;
        this.severity = severity;
        this.createdAt = createdAt;
        this.links = links;
    }

    public String getExceptionId() {
        return exceptionId;
    }

    public void setExceptionId(String exceptionId) {
        this.exceptionId = exceptionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Links getLinks() {
        return links;
    }

    public void setLinks(Links links) {
        this.links = links;
    }

    /** Self/investigation hyperlinks, per the {@code Exception} contract schema. */
    public static class Links {

        @JsonProperty("self")
        private String self;

        @JsonProperty("investigation")
        private String investigation;

        public Links() {
        }

        public Links(String self, String investigation) {
            this.self = self;
            this.investigation = investigation;
        }

        public String getSelf() {
            return self;
        }

        public void setSelf(String self) {
            this.self = self;
        }

        public String getInvestigation() {
            return investigation;
        }

        public void setInvestigation(String investigation) {
            this.investigation = investigation;
        }
    }
}
