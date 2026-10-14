package com.trace.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /internal/v1/investigate} — must match
 * {@code InvestigationRequest} in {@code contracts/openapi/internal-api.yaml}
 * exactly. {@code tolerance_pct} and {@code callback_url} are optional in the
 * contract and not sent yet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvestigationRequest(
        @JsonProperty("investigation_id") String investigationId,
        @JsonProperty("exception_id") String exceptionId,
        @JsonProperty("exception_type") String exceptionType,
        @JsonProperty("references") References references,
        @JsonProperty("context") Context context) {

    public record References(
            @JsonProperty("invoice_id") String invoiceId,
            @JsonProperty("po_id") String poId,
            @JsonProperty("vendor_id") String vendorId) {
    }

    public record Context(
            @JsonProperty("confidence_threshold") int confidenceThreshold) {
    }
}
