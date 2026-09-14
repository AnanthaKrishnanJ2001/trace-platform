package com.trace.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the TRACE Spring Boot Core service.
 *
 * Owns: API gateway / orchestration entry point, exception lifecycle,
 * persistence (PostgreSQL), human-approval workflow, action execution,
 * and audit logging. See docs/architecture for the full technical design
 * and contracts/openapi for the request/response contracts this service
 * exposes and consumes.
 */
@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
