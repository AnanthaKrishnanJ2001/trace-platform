package com.trace.orchestrator.repository;

import com.trace.orchestrator.domain.ExceptionEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ExceptionRepository extends JpaRepository<ExceptionEntity, String> {

    /**
     * Atomically allocates the next value of {@code exception_id_seq}
     * (created in {@code V1__baseline_schema.sql}) so exception_id
     * generation is safe under concurrent inserts without a
     * select-then-increment race.
     */
    @Query(value = "SELECT nextval('exception_id_seq')", nativeQuery = true)
    long nextExceptionSequence();

    /**
     * Moves an exception from {@code from} to {@code to} <em>only if</em> it
     * is still in {@code from}, in one statement — the database, not Java,
     * decides which of several racing callers wins. Commits on its own
     * (short transaction) so the new status is visible to everyone before any
     * slow work starts.
     *
     * @return 1 if this caller made the transition, 0 if the row was not in
     *         {@code from} (someone else got there first, or it doesn't exist)
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExceptionEntity e set e.status = :to, e.updatedAt = :now "
            + "where e.exceptionId = :id and e.status = :from")
    int transitionStatus(@Param("id") String id, @Param("from") String from, @Param("to") String to,
            @Param("now") Instant now);

    /**
     * Same guarded transition as {@link #transitionStatus}, also recording the
     * root cause the investigation produced.
     *
     * @return 1 if applied, 0 if the row was no longer in {@code from}
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ExceptionEntity e set e.status = :to, e.rootCauseLabel = :label, "
            + "e.rootCauseConfidence = :confidence, e.updatedAt = :now "
            + "where e.exceptionId = :id and e.status = :from")
    int recordInvestigationOutcome(@Param("id") String id, @Param("from") String from, @Param("to") String to,
            @Param("label") String rootCauseLabel, @Param("confidence") Short rootCauseConfidence,
            @Param("now") Instant now);

    /** Ids of exceptions in {@code status} with one of {@code severities}, untouched since {@code before}, oldest first. */
    @Query("select e.exceptionId from ExceptionEntity e where e.status = :status "
            + "and e.severity in :severities and e.updatedAt < :before order by e.updatedAt")
    List<String> findIdsByStatusAndSeverityUpdatedBefore(@Param("status") String status,
            @Param("severities") Collection<String> severities, @Param("before") Instant before, Pageable pageable);

    /** Ids of exceptions in {@code status} untouched since {@code before}, oldest first. */
    @Query("select e.exceptionId from ExceptionEntity e where e.status = :status "
            + "and e.updatedAt < :before order by e.updatedAt")
    List<String> findIdsByStatusUpdatedBefore(@Param("status") String status, @Param("before") Instant before,
            Pageable pageable);
}
