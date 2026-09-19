package com.trace.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Maps to the {@code invoices} table (TDD §4.1). Read-only from this
 * service's perspective for TASK-02 — the classifier looks up
 * {@code invoice_amount} via the {@code exceptions.invoice_id} FK; nothing
 * in this codebase writes invoice rows yet (they're populated upstream of
 * TRACE, e.g. by an ERP sync not modeled in the current backlog).
 */
@Entity
@Table(name = "invoices")
public class InvoiceEntity {

    @Id
    @Column(name = "invoice_id", length = 20)
    private String invoiceId;

    @Column(name = "vendor_id", length = 20, nullable = false)
    private String vendorId;

    @Column(name = "po_id", length = 20)
    private String poId;

    @Column(name = "invoice_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal invoiceAmount;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InvoiceEntity() {
        // JPA
    }

    public InvoiceEntity(String invoiceId, String vendorId, String poId, BigDecimal invoiceAmount,
            LocalDate invoiceDate, String status, Instant createdAt) {
        this.invoiceId = invoiceId;
        this.vendorId = vendorId;
        this.poId = poId;
        this.invoiceAmount = invoiceAmount;
        this.invoiceDate = invoiceDate;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public String getVendorId() {
        return vendorId;
    }

    public String getPoId() {
        return poId;
    }

    public BigDecimal getInvoiceAmount() {
        return invoiceAmount;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InvoiceEntity that)) {
            return false;
        }
        return Objects.equals(invoiceId, that.invoiceId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(invoiceId);
    }
}
