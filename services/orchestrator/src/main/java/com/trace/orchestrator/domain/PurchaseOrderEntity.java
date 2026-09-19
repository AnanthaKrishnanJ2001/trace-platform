package com.trace.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps to the {@code purchase_orders} table (TDD §4.1). Read-only from this
 * service's perspective for TASK-02 — the classifier looks up
 * {@code approved_amount} via the {@code exceptions.po_id} FK; nothing in
 * this codebase writes PO rows yet (they're populated upstream of TRACE,
 * e.g. by an ERP sync not modeled in the current backlog).
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrderEntity {

    @Id
    @Column(name = "po_id", length = 20)
    private String poId;

    @Column(name = "vendor_id", length = 20, nullable = false)
    private String vendorId;

    @Column(name = "approved_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal approvedAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PurchaseOrderEntity() {
        // JPA
    }

    public PurchaseOrderEntity(String poId, String vendorId, BigDecimal approvedAmount, String currency,
            String status, Instant createdAt) {
        this.poId = poId;
        this.vendorId = vendorId;
        this.approvedAmount = approvedAmount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getPoId() {
        return poId;
    }

    public String getVendorId() {
        return vendorId;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public String getCurrency() {
        return currency;
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
        if (!(o instanceof PurchaseOrderEntity that)) {
            return false;
        }
        return Objects.equals(poId, that.poId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(poId);
    }
}
