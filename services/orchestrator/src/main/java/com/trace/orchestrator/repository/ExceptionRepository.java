package com.trace.orchestrator.repository;

import com.trace.orchestrator.domain.ExceptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExceptionRepository extends JpaRepository<ExceptionEntity, String> {

    /**
     * Atomically allocates the next value of {@code exception_id_seq}
     * (created in {@code V1__baseline_schema.sql}) so exception_id
     * generation is safe under concurrent inserts without a
     * select-then-increment race.
     */
    @Query(value = "SELECT nextval('exception_id_seq')", nativeQuery = true)
    long nextExceptionSequence();
}
