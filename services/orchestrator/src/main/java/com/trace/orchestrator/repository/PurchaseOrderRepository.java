package com.trace.orchestrator.repository;

import com.trace.orchestrator.domain.PurchaseOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, String> {
}
