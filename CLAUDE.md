# TRACE — Repository Guide for Claude Code

This file is always loaded when Claude Code works anywhere in this repo. It's the
equivalent of a root `.github/copilot-instructions.md` — keep it short and durable;
put anything specific to one service in that service's own `CLAUDE.md` instead
(Claude Code merges the nearest one up the directory tree with this one automatically).

## What TRACE is

Transactional Root-cause Analysis & Context Engine. Two decoupled backend services
that talk only over a versioned internal REST contract — nothing else is shared:

| Service | Path | Owns | Stack |
|---|---|---|---|
| Orchestrator (Spring Boot Core) | `services/orchestrator/` | API gateway, exception lifecycle, human-approval workflow, action execution, audit logging, the PostgreSQL system of record | Java 21, Spring Boot 3.3, Maven |
| Engine (FastAPI AI Service) | `services/engine/` | Multi-agent investigation (Orchestrator/Investigation/Policy/Memory/Action agents), pgvector-backed semantic search | Python 3.12, FastAPI, Poetry |

Full technical design: `docs/architecture/`. Why this is one repo, not two: `docs/decisions/0001-repository-strategy.md`.

## Repository-wide rules (apply to both services)

- **Contracts are the source of truth.** `contracts/openapi/*.yaml` and
  `contracts/schemas/error-envelope.schema.json` define every request/response shape
  crossing a service boundary. Change the contract file first, then update the
  implementation on both sides that consume it — never let a service's code drift
  from what's in `contracts/`.
- **Every 4xx/5xx response, from either service, uses the standard error envelope**
  in `contracts/schemas/error-envelope.schema.json` (`timestamp`, `status`, `error`,
  `message`, `path`, `trace_id`, optional `details`). Never let a raw stack trace or
  framework default error page reach a client.
- **`trace_id` is generated once, at the Spring Boot Core gateway,** and propagated
  as the `X-Trace-Id` header on every downstream call so both services' logs can be
  joined for one request. Never drop it, never regenerate it partway through a request.
- **Internal service-to-service calls are authenticated with a signed JWT**, issued
  by Spring Boot Core and audience-restricted to the FastAPI service. Never add a new
  internal call that skips this. Never log the token or the signing secret.
- **Local dev is `docker compose` at `infra/docker/docker-compose.yml`**, using
  `infra/docker/.env` (copy from `.env.example`, never commit the real `.env`).
- **Don't invent new top-level directories** without updating
  `docs/decisions/0001-repository-strategy.md` — the layout there is deliberate
  (see "Two structural notes" at the end of that ADR).

## Working here

- Check the backlog's acceptance criteria (Gherkin, in the product backlog) for the
  story/task you're implementing before writing code — if it's ambiguous, say so
  before guessing.
- Read the existing implementation of a similar endpoint/agent/table before adding a
  new one, and match its pattern rather than introducing a second convention.
- Service-specific conventions (package layout, testing framework, resilience
  patterns, DB migration rules, etc.) live in each service's own `CLAUDE.md` —
  see `services/orchestrator/CLAUDE.md` for the Spring Boot Core rules.
