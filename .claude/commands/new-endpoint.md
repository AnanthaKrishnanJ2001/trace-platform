---
description: Scaffold a new REST endpoint in the orchestrator service following TRACE's layering, contract, and error-envelope conventions.
---

Scaffold a new endpoint in `services/orchestrator` for: $ARGUMENTS

Before writing any code:

1. Read `services/orchestrator/CLAUDE.md` and the root `CLAUDE.md` for the
   conventions this must follow.
2. Check whether this endpoint is already described in
   `contracts/openapi/client-api.yaml` or `internal-api.yaml`. If it is, the
   contract is the source of truth for the request/response shape — implement
   to match it exactly. If it isn't yet, add it there first, then implement
   to match what you just wrote.
3. Check the product backlog for the relevant story/task's acceptance
   criteria (Gherkin). If you can't find a clear match or the criteria are
   ambiguous, say so before proceeding rather than guessing.

Then implement, following the package layout in
`services/orchestrator/CLAUDE.md`:

- A DTO (or reuse an existing one) in `dto/` matching the contract exactly
- A thin controller method in `controller/` — mapping and delegation only
- The actual logic in `service/`, including any status-transition or
  business-rule checks
- Jakarta Bean Validation on the request DTO using the exact constraints from
  the technical design doc (§3.3) where they apply
- Error handling that returns the standard error envelope
  (`contracts/schemas/error-envelope.schema.json`) for every failure path,
  with the correct HTTP status code
- An audit log entry if this endpoint has financial or state consequences
- SLF4J logging through the full flow — the controller logs the inbound
  request (key fields, not secrets) and the outcome; the service logs any
  generated ID/state change and confirms success or failure of persistence
  before returning or letting an exception propagate. Use `debug` for
  routine/expected detail, `info` for a completed business outcome, and
  `warn`/`error` only for an actual failure — never for an expected
  validation rejection. Rely on `TraceIdFilter`'s MDC `trace_id` for request
  correlation rather than generating or threading a new id, and never log a
  secret, token, JWT, or full request/response body that could contain one
- A test (JUnit 5 + Mockito, `@WebMvcTest` where possible) covering the happy
  path and at least one error case

Finish by listing: the contract file(s) you added or confirmed against, the
files you created/changed, and any acceptance criteria you couldn't verify
because they weren't specified.
