# Orchestrator (Spring Boot Core) — Rules for Claude Code

Scope: everything under `services/orchestrator/`. This is the Copilot-style
"targeted instructions for a path" equivalent — Claude Code loads this
automatically whenever you're working inside this folder, on top of the
repo-wide rules in the root `CLAUDE.md`.

## Tech stack (pinned — see `pom.xml`, don't silently change versions)

- Java 21, Spring Boot 3.3.4, built with Maven (`./mvnw`)
- Persistence: Spring Data JPA + PostgreSQL, migrations via Flyway
  (`infra/db/migrations/`, loaded from both `classpath:db/migration` and that
  filesystem path per `application.yml`)
- Security: Spring Security + OAuth2 resource server, for validating inbound
  JWTs and issuing the internal service-to-service JWT
- Resilience: Resilience4j (circuit breaker + retry + time limiter), already
  configured in `application.yml` under the `investigationService` instance
  name — reuse it, don't hand-roll a new resilience wrapper
- Cache: Spring Data Redis, cache-aside pattern (see TDD §4.2 for TTLs and
  invalidation rules per cache key)
- Test: JUnit 5 + Mockito (`spring-boot-starter-test`)

## Package layout

Follow this structure under `com.trace.orchestrator` — don't put business
logic directly in a controller or a JPA entity:

```
com.trace.orchestrator/
├── controller/     # REST endpoints only — request/response mapping and
│                   # delegation to a service. No business logic here.
├── service/        # Business logic, orchestration, transaction boundaries
├── repository/     # Spring Data JPA repositories
├── domain/         # JPA entities — one per table in the DDL (TDD §4.1)
├── dto/            # Request/response DTOs — must match contracts/openapi/*.yaml
│                   # exactly; if they diverge, the contract file is wrong and
│                   # needs updating first, not the other way around
├── client/         # Outbound clients (e.g. the FastAPI AI Service client),
│                   # each call wrapped in the existing Resilience4j instance
├── security/       # JWT issuance/validation, filters
├── exception/       # Exception classes + the @ControllerAdvice that maps them
│                   # to the standard error envelope
└── config/         # Spring configuration classes
```

## Persistence rules

- Entities map exactly to the DDL in TDD §4.1 — table names, column names,
  types, and constraints (e.g. `root_cause_confidence SMALLINT CHECK (...
  BETWEEN 0 AND 100)`) are authoritative. If a story needs a schema change,
  add a new numbered Flyway migration under `infra/db/migrations/`; never
  edit a migration that has already been merged.
- Domain-table primary keys are app-generated business IDs (`VARCHAR(20)`,
  e.g. `EXC-88231`, `INV-2026-004471`) — don't switch these to auto-increment
  surrogate keys. `agent_traces`, `audit_logs`, and `users` use `BIGSERIAL`/
  their own key shape as already defined in the DDL — match what's there.
- Every status transition on `exceptions` (`DETECTED` → `INVESTIGATING` →
  `INVESTIGATED` / `NEEDS_HUMAN_REVIEW` → `RESOLVED` / `REJECTED` /
  `INVESTIGATION_UNAVAILABLE`) must be a valid transition — reject an invalid
  one with `409 Conflict` and the standard error envelope, per TDD §3.3.

## API & validation rules

- Validate every inbound payload with Jakarta Bean Validation using the exact
  constraints in TDD §3.3 (e.g. `references.invoice_id|po_id|vendor_id`
  pattern `^[A-Z]{2,4}-\d{4,8}$`, ISO 4217 currency, `decision` enum of
  `APPROVE`/`REJECT`/`INVESTIGATE_FURTHER`) — reuse these, don't redefine
  looser versions.
- Map validation and business failures to the HTTP status codes in TDD §3.3
  (400 validation, 401/403 auth, 404 not found, 409 invalid transition, 422
  semantically invalid, 503 breaker open, 504 downstream timeout) — via the
  standard error envelope, never a default Spring error body.
- Any new or changed endpoint gets added to `contracts/openapi/client-api.yaml`
  (or `internal-api.yaml` for calls to the AI service) in the same change —
  not as a follow-up.

## Calling the FastAPI AI Service

- Always go through the existing Resilience4j `investigationService`
  circuit breaker + retry + time limiter config (`application.yml`) — never
  add a new unguarded `RestTemplate`/`WebClient` call to `engine`.
- Prefer `CompletableFuture`/reactive (`WebClient`) over raw `Thread` for
  these calls, matching the async style the time limiter expects.
- Every request carries the internal JWT (audience `fastapi-ai-service`) and
  the `X-Trace-Id` header — see the root `CLAUDE.md`.
- On circuit-open or timeout, set the exception's status to
  `INVESTIGATION_UNAVAILABLE` and return `503`/`504` per TDD §5.3 — don't let
  the request hang or surface a raw connection error to the client.

## Testing

- Unit test services and validators with JUnit 5 + Mockito; prefer
  `@WebMvcTest` for controllers and `@DataJpaTest` for repositories over a
  full `@SpringBootTest` unless the test genuinely needs the whole context.
- Use `@ParameterizedTest` for anything table-shaped: severity bands (LOW
  <5%, MEDIUM 5–15%, HIGH 15–30%, CRITICAL >30%), validation edge cases,
  status-transition matrices.
- A new endpoint or status transition isn't done until it has a test proving
  both the happy path and at least one rejected/error case from TDD §3.3.

## Anti-patterns — don't do these

- No business logic in `controller/` or `domain/` — it belongs in `service/`.
- No hardcoded credentials, URLs, or secrets — use the `application.yml`
  placeholders (`${DB_HOST}`, `${INTERNAL_JWT_SECRET}`, etc.) already wired to
  `infra/docker/.env.example`.
- No raw `Thread.sleep` in tests — use Awaitility or synchronous test doubles.
- No modifying a shared contract or a persisted entity's shape without
  checking who else reads it (the FastAPI service, the frontend) first.
- No skipping the audit log — every action with financial or state
  consequences (approve/reject, action execution) writes to `audit_logs` in
  the same transaction as the state change it's recording.
