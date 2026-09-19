package com.trace.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trace.orchestrator.config.SeverityBandsProperties;
import com.trace.orchestrator.domain.InvoiceEntity;
import com.trace.orchestrator.domain.PurchaseOrderEntity;
import com.trace.orchestrator.dto.ExceptionEventRequest;
import com.trace.orchestrator.dto.ExceptionResponse;
import com.trace.orchestrator.exception.ReferencedRecordNotFoundException;
import com.trace.orchestrator.repository.ExceptionRepository;
import com.trace.orchestrator.repository.InvoiceRepository;
import com.trace.orchestrator.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@link ExceptionService}'s TASK-02 additions:
 * classification runs synchronously during ingest, sourced from the linked
 * invoice/PO rows (not the request's {@code raw_amount}), and a missing
 * reference is rejected before an exception row is ever persisted.
 */
@ExtendWith(MockitoExtension.class)
class ExceptionServiceTest {

    @Mock
    private ExceptionRepository exceptionRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    private final ExceptionClassifier exceptionClassifier = new ExceptionClassifier(new SeverityBandsProperties());

    @Test
    void ingest_classifiesFromLinkedInvoiceAndPo_andPersistsClassifiedRow() {
        ExceptionService service = new ExceptionService(exceptionRepository, invoiceRepository,
                purchaseOrderRepository, exceptionClassifier);

        ExceptionEventRequest request = validRequest();
        when(invoiceRepository.findById("INV-10492")).thenReturn(Optional.of(
                new InvoiceEntity("INV-10492", "VEND-3391", "PO-7821", new BigDecimal("52000.00"), LocalDate.now(),
                        "PENDING", Instant.now())));
        when(purchaseOrderRepository.findById("PO-7821")).thenReturn(Optional.of(
                new PurchaseOrderEntity("PO-7821", "VEND-3391", new BigDecimal("45000.00"), "INR", "OPEN",
                        Instant.now())));
        when(exceptionRepository.nextExceptionSequence()).thenReturn(88231L);

        ExceptionResponse response = service.ingest(request);

        assertThat(response.getExceptionId()).isEqualTo("EXC-88231");
        assertThat(response.getStatus()).isEqualTo("DETECTED");
        assertThat(response.getType()).isEqualTo("PO_INVOICE_MISMATCH");
        // (52000 - 45000) / 45000 * 100 = 15.5555...% -> HIGH band (>=15, <30)
        assertThat(response.getSeverity()).isEqualTo("HIGH");
        verify(exceptionRepository).save(any());
    }

    @Test
    void ingest_whenInvoiceNotFound_throwsAndNeverPersists() {
        ExceptionService service = new ExceptionService(exceptionRepository, invoiceRepository,
                purchaseOrderRepository, exceptionClassifier);

        ExceptionEventRequest request = validRequest();
        when(invoiceRepository.findById("INV-10492")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest(request))
                .isInstanceOf(ReferencedRecordNotFoundException.class)
                .hasMessageContaining("INV-10492");

        verify(exceptionRepository, never()).save(any());
    }

    @Test
    void ingest_whenPurchaseOrderNotFound_throwsAndNeverPersists() {
        ExceptionService service = new ExceptionService(exceptionRepository, invoiceRepository,
                purchaseOrderRepository, exceptionClassifier);

        ExceptionEventRequest request = validRequest();
        when(invoiceRepository.findById("INV-10492")).thenReturn(Optional.of(
                new InvoiceEntity("INV-10492", "VEND-3391", "PO-7821", new BigDecimal("52000.00"), LocalDate.now(),
                        "PENDING", Instant.now())));
        when(purchaseOrderRepository.findById("PO-7821")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest(request))
                .isInstanceOf(ReferencedRecordNotFoundException.class)
                .hasMessageContaining("PO-7821");

        verify(exceptionRepository, never()).save(any());
    }

    private static ExceptionEventRequest validRequest() {
        ExceptionEventRequest request = new ExceptionEventRequest();
        request.setSourceSystem("SAP_AP_MODULE");
        request.setEventType("PAYMENT_EXCEPTION");
        request.setRawAmount(new BigDecimal("52000.00"));
        request.setCurrency("INR");
        request.setEventTimestamp(Instant.parse("2026-09-15T09:41:12Z"));

        ExceptionEventRequest.References references = new ExceptionEventRequest.References();
        references.setInvoiceId("INV-10492");
        references.setPoId("PO-7821");
        references.setVendorId("VEND-3391");
        request.setReferences(references);

        return request;
    }
}
