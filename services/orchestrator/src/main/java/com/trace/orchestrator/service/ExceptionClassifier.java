package com.trace.orchestrator.service;

import com.trace.orchestrator.config.SeverityBandsProperties;
import com.trace.orchestrator.constant.ServiceConstants;
import com.trace.orchestrator.domain.InvoiceEntity;
import com.trace.orchestrator.domain.PurchaseOrderEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Rule-based exception classifier (US-01/TASK-02). Deterministic — no LLM
 * call — matching the story's premise that triage thresholds are exact.
 *
 * <p>The only rule this task's AC specifies is deviation-based severity for
 * an invoice/PO amount mismatch, so {@code PO_INVOICE_MISMATCH} is currently
 * the only {@code type} this classifier can produce; every exception event
 * TASK-01 accepts carries both an invoice and a PO reference, and comparing
 * those two amounts is the only deterministic rule the TDD/backlog define.
 * The backlog's EPIC-2 test matrix (US-06) names other exception categories
 * (duplicate invoice, vendor mismatch, policy violation) that this
 * classifier does not yet detect — no rule for them exists anywhere in the
 * TDD, so adding one here would be invented, not derived. When a second
 * deterministic rule is specified, add a second branch/strategy here rather
 * than overloading this one.
 */
@Component
public class ExceptionClassifier {

    private final SeverityBandsProperties severityBands;

    public ExceptionClassifier(SeverityBandsProperties severityBands) {
        this.severityBands = severityBands;
    }

    /**
     * Classifies a PO/invoice pair by the absolute deviation of
     * {@code invoice_amount} from the linked PO's {@code approved_amount},
     * per the configured severity bands.
     */
    public ClassificationResult classify(InvoiceEntity invoice, PurchaseOrderEntity purchaseOrder) {
        BigDecimal deviationPct = deviationPct(invoice.getInvoiceAmount(), purchaseOrder.getApprovedAmount());
        String severity = severityFor(deviationPct);
        return new ClassificationResult(ServiceConstants.TYPE_PO_INVOICE_MISMATCH, severity, deviationPct);
    }

    private BigDecimal deviationPct(BigDecimal invoiceAmount, BigDecimal approvedAmount) {
        return invoiceAmount.subtract(approvedAmount).abs()
                .divide(approvedAmount, ServiceConstants.DEVIATION_SCALE, RoundingMode.HALF_UP)
                .multiply(ServiceConstants.HUNDRED);
    }

    /** Bands are half-open on the upper bound: a value AT a threshold belongs to the next, more severe band. */
    private String severityFor(BigDecimal deviationPct) {
        if (deviationPct.compareTo(severityBands.getLowMaxPct()) < 0) {
            return ServiceConstants.SEVERITY_LOW;
        }
        if (deviationPct.compareTo(severityBands.getMediumMaxPct()) < 0) {
            return ServiceConstants.SEVERITY_MEDIUM;
        }
        if (deviationPct.compareTo(severityBands.getHighMaxPct()) < 0) {
            return ServiceConstants.SEVERITY_HIGH;
        }
        return ServiceConstants.SEVERITY_CRITICAL;
    }

    public record ClassificationResult(String type, String severity, BigDecimal deviationPct) {
    }
}
