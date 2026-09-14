package com.trace.orchestrator.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight liveness endpoint, separate from Spring Boot Actuator's
 * /actuator/health, so docker-compose healthchecks and the frontend
 * dashboard have a stable, dependency-free endpoint to poll.
 *
 * Replace/extend with real controllers as the backlog stories under
 * EPIC-1 (exception ingestion) and beyond are implemented.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "orchestrator",
                "timestamp", Instant.now().toString());
    }
}
