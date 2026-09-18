package com.trace.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.trace.orchestrator.dto.validation.RegisteredUpstreamSystem;
import com.trace.orchestrator.dto.validation.ValidCurrency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request body for {@code POST /api/v1/exceptions} — must match
 * {@code ExceptionEvent} in {@code contracts/openapi/client-api.yaml}
 * exactly (TDD §3.1, §3.3).
 */
public class ExceptionEventRequest {

    @NotBlank
    @Size(max = 50)
    @RegisteredUpstreamSystem
    @JsonProperty("source_system")
    private String sourceSystem;

    @NotBlank
    @JsonProperty("event_type")
    private String eventType;

    @NotNull
    @Valid
    @JsonProperty("references")
    private References references;

    @NotNull
    @Positive
    @Digits(integer = 12, fraction = 2)
    @JsonProperty("raw_amount")
    private BigDecimal rawAmount;

    @NotBlank
    @ValidCurrency
    @JsonProperty("currency")
    private String currency;

    @NotNull
    @JsonProperty("event_timestamp")
    private Instant eventTimestamp;

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public References getReferences() {
        return references;
    }

    public void setReferences(References references) {
        this.references = references;
    }

    public BigDecimal getRawAmount() {
        return rawAmount;
    }

    public void setRawAmount(BigDecimal rawAmount) {
        this.rawAmount = rawAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(Instant eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    /** The invoice/PO/vendor identifiers this exception event is about. */
    public static class References {

        private static final String ID_PATTERN = "^[A-Z]{2,4}-\\d{4,8}$";

        @NotBlank
        @Pattern(regexp = ID_PATTERN)
        @JsonProperty("invoice_id")
        private String invoiceId;

        @NotBlank
        @Pattern(regexp = ID_PATTERN)
        @JsonProperty("po_id")
        private String poId;

        @NotBlank
        @Pattern(regexp = ID_PATTERN)
        @JsonProperty("vendor_id")
        private String vendorId;

        public String getInvoiceId() {
            return invoiceId;
        }

        public void setInvoiceId(String invoiceId) {
            this.invoiceId = invoiceId;
        }

        public String getPoId() {
            return poId;
        }

        public void setPoId(String poId) {
            this.poId = poId;
        }

        public String getVendorId() {
            return vendorId;
        }

        public void setVendorId(String vendorId) {
            this.vendorId = vendorId;
        }
    }
}
