package com.trace.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * Maps to the {@code exceptions} table (TDD §4.1). Named {@code ExceptionEntity}
 * rather than {@code Exception} — the DDL/contract's domain name — solely to
 * avoid shadowing {@link java.lang.Exception} in every class that imports it;
 * the table name, column names, and JSON field names it maps to/from are
 * otherwise unchanged.
 *
 * <p>Only the columns TASK-01 (ingestion) writes are populated here;
 * {@code type}/{@code severity}/{@code root_cause_*} are left {@code null}
 * for TASK-02 (classification) to fill in.
 */
@Entity
@Table(name = "exceptions")
public class ExceptionEntity {

    @Id
    @Column(name = "exception_id", length = 20)
    private String exceptionId;

    @Column(name = "source_system", length = 50, nullable = false)
    private String sourceSystem;

    @Column(name = "type", length = 50)
    private String type;

    @Column(name = "severity", length = 10)
    private String severity;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "invoice_id", length = 20)
    private String invoiceId;

    @Column(name = "po_id", length = 20)
    private String poId;

    @Column(name = "vendor_id", length = 20)
    private String vendorId;

    @Column(name = "root_cause_label", length = 80)
    private String rootCauseLabel;

    @Column(name = "root_cause_confidence")
    private Short rootCauseConfidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExceptionEntity() {
        // JPA
    }

    public ExceptionEntity(String exceptionId, String sourceSystem, String status, String invoiceId,
            String poId, String vendorId, Instant createdAt, Instant updatedAt) {
        this.exceptionId = exceptionId;
        this.sourceSystem = sourceSystem;
        this.status = status;
        this.invoiceId = invoiceId;
        this.poId = poId;
        this.vendorId = vendorId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getExceptionId() {
        return exceptionId;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getType() {
        return type;
    }

    public String getSeverity() {
        return severity;
    }

    public String getStatus() {
        return status;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public String getPoId() {
        return poId;
    }

    public String getVendorId() {
        return vendorId;
    }

    public String getRootCauseLabel() {
        return rootCauseLabel;
    }

    public Short getRootCauseConfidence() {
        return rootCauseConfidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExceptionEntity that)) {
            return false;
        }
        return Objects.equals(exceptionId, that.exceptionId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(exceptionId);
    }
}
