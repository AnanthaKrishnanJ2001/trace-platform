package com.trace.orchestrator.repository;

import com.trace.orchestrator.domain.InvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<InvoiceEntity, String> {
}
