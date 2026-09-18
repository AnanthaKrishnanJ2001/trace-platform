package com.trace.orchestrator.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that {@code source_system} matches a registered upstream system
 * code (TDD §3.3). The registry is the configurable allow-list at
 * {@code trace.upstream-systems} in {@code application.yml}.
 *
 * <p><strong>Known gap:</strong> neither the TDD nor the product backlog
 * defines the actual list of registered upstream system codes — the only
 * one that appears anywhere is the example {@code SAP_AP_MODULE}. The
 * default list below is a placeholder seeded with just that value; a real
 * registry (config-driven or a DB-backed admin table) should replace it
 * before this validates production traffic from more than one system.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RegisteredUpstreamSystemValidator.class)
public @interface RegisteredUpstreamSystem {

    String message() default "must be a registered upstream system code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
