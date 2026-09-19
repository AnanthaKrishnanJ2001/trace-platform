package com.trace.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trace.orchestrator.config.SeverityBandsProperties;
import com.trace.orchestrator.constant.ServiceConstants;
import com.trace.orchestrator.domain.InvoiceEntity;
import com.trace.orchestrator.domain.PurchaseOrderEntity;
import com.trace.orchestrator.service.ExceptionClassifier.ClassificationResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Severity-band boundary coverage for TASK-02's AC (LOW &lt;5%, MEDIUM
 * 5-15%, HIGH 15-30%, CRITICAL &gt;30%), plus the AC's own 15.5% -> HIGH
 * scenario. Approved amount is a fixed round number (10,000.00) across the
 * boundary cases so each invoice amount produces an exact, rounding-free
 * deviation percentage.
 */
class ExceptionClassifierTest {

    private static final BigDecimal APPROVED_AMOUNT = new BigDecimal("10000.00");

    private final ExceptionClassifier classifier = new ExceptionClassifier(defaultSeverityBands());

    private static SeverityBandsProperties defaultSeverityBands() {
        SeverityBandsProperties properties = new SeverityBandsProperties();
        properties.setLowMaxPct(BigDecimal.valueOf(5));
        properties.setMediumMaxPct(BigDecimal.valueOf(15));
        properties.setHighMaxPct(BigDecimal.valueOf(30));
        return properties;
    }

    @ParameterizedTest(name = "invoice={0} vs po={1} -> {2}")
    @CsvSource({
            "10499.00, 10000.00, LOW",       // 4.99% - just under the LOW/MEDIUM boundary
            "10500.00, 10000.00, MEDIUM",    // 5.00% - exactly on the boundary -> next band
            "11499.00, 10000.00, MEDIUM",    // 14.99% - just under the MEDIUM/HIGH boundary
            "11500.00, 10000.00, HIGH",      // 15.00% - exactly on the boundary -> next band
            "11550.00, 10000.00, HIGH",      // 15.50% - the AC's own scenario
            "12999.00, 10000.00, HIGH",      // 29.99% - just under the HIGH/CRITICAL boundary
            "13000.00, 10000.00, CRITICAL",  // 30.00% - exactly on the boundary -> next band
    })
    void classify_appliesConfiguredSeverityBandsAtExactBoundaries(BigDecimal invoiceAmount,
            BigDecimal approvedAmount, String expectedSeverity) {
        InvoiceEntity invoice = invoiceOf(invoiceAmount);
        PurchaseOrderEntity purchaseOrder = purchaseOrderOf(approvedAmount);

        ClassificationResult result = classifier.classify(invoice, purchaseOrder);

        assertThat(result.severity()).isEqualTo(expectedSeverity);
        assertThat(result.type()).isEqualTo(ServiceConstants.TYPE_PO_INVOICE_MISMATCH);
    }

    @Test
    void classify_withAcScenario_15_5PercentDeviationOfPoAmount_returnsHigh() {
        InvoiceEntity invoice = invoiceOf(new BigDecimal("11550.00"));
        PurchaseOrderEntity purchaseOrder = purchaseOrderOf(APPROVED_AMOUNT);

        ClassificationResult result = classifier.classify(invoice, purchaseOrder);

        assertThat(result.type()).isEqualTo("PO_INVOICE_MISMATCH");
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.deviationPct()).isEqualByComparingTo("15.5");
    }

    @Test
    void classify_whenInvoiceAmountBelowApprovedAmount_usesAbsoluteDeviation() {
        InvoiceEntity invoice = invoiceOf(new BigDecimal("8000.00"));
        PurchaseOrderEntity purchaseOrder = purchaseOrderOf(APPROVED_AMOUNT);

        ClassificationResult result = classifier.classify(invoice, purchaseOrder);

        assertThat(result.deviationPct()).isEqualByComparingTo("20.0");
        assertThat(result.severity()).isEqualTo("HIGH");
    }

    private static InvoiceEntity invoiceOf(BigDecimal invoiceAmount) {
        return new InvoiceEntity("INV-10492", "VEND-3391", "PO-7821", invoiceAmount, LocalDate.now(), "PENDING",
                Instant.now());
    }

    private static PurchaseOrderEntity purchaseOrderOf(BigDecimal approvedAmount) {
        return new PurchaseOrderEntity("PO-7821", "VEND-3391", approvedAmount, "INR", "OPEN", Instant.now());
    }
}
