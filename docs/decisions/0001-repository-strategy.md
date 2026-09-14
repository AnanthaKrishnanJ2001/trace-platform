# TRACE — Repository Strategy Recommendation

**Prepared for:** TRACE engineering (Spring Boot Core + FastAPI AI Service)
**Date:** 2026-09-14
**Status:** Recommendation — Monorepo
**Context inputs:** TRACE_Technical_Design_Document.pdf, TRACE_Product_Backlog.xlsx

## 1. Context

TRACE is a two-service, decoupled architecture:

| Service | Responsibility | Tooling |
|---|---|---|
| Spring Boot Core | API gateway, orchestration, exception lifecycle, human-approval workflow, action execution, audit logging | Java 21, Spring Boot, Maven/Gradle |
| FastAPI AI Service | Multi-agent investigation engine (Orchestrator, Investigation, Policy, Memory, Action agents) | Python 3.12, FastAPI, Poetry/Pipenv |

Both services share one PostgreSQL instance (system of record + pgvector for embeddings) and Redis (cache-aside). They communicate exclusively over a versioned internal REST contract (`/internal/v1/investigate`, `/internal/v1/memory/write-back`), authenticated with a Spring-Boot-issued JWT. TASK-19 in the backlog explicitly calls for this contract to be "verified by a contract test run in CI" against both services from a single OpenAPI spec.

Two constraints from the backlog matter more than usual for this decision:

- **Timeline:** every P0 story targets a demo on September 27, 2026 — roughly 13 days from today. Anything that adds process overhead without adding reliability is a cost the team can't easily absorb right now.
- **Team shape:** this reads as a small, tightly coupled team (a Java engineer, an AI/Python engineer, shared frontend work) building one product, not multiple teams with independent roadmaps.

## 2. Monorepo vs. Multi-repo Trade-offs

| Factor | Monorepo (`/services/orchestrator`, `/services/engine`) | Multi-repo (`trace-orchestrator`, `trace-engine`) |
|---|---|---|
| **CI/CD pipelines** | One pipeline definition, path-filtered jobs (GitHub Actions `paths:` / GitLab `rules:changes:`) so a Java-only change doesn't trigger the Python job and vice versa. Contract tests run in the same pipeline against both services with no publish/pull step. | Each repo gets its own clean pipeline, no path-filter logic needed. But the contract test (TASK-19) now needs either a third "integration" repo, a scheduled cross-repo job, or a published/versioned contract artifact both sides pull — real setup work before day 1 of coding. |
| **Independent deployment lifecycles** | Fully preserved — deployment is a function of the built container image, not the repo. Each service still gets its own Dockerfile, its own image tag, its own release cadence; nothing about a shared repo forces synchronized deploys. | Same independence, at the cost of coordinating two repos' tags/releases when a change spans both (e.g., a new field added to the internal contract). |
| **Cross-language tooling (Maven/Gradle vs. Poetry/Pipenv)** | No conflict — Maven/Gradle only ever looks inside `services/orchestrator/`, Poetry/Pipenv only inside `services/engine/`. Each keeps its own lockfile, its own venv/target dir. IDEs (IntelliJ for the Java side, VS Code/PyCharm for Python) open the subfolder as its own project root. | Each tool already lives in its own repo root — marginally simpler tool config, but this is the one area where monorepo cost is genuinely near zero, not an advantage for multi-repo. |
| **Local Docker Compose orchestration** | One root `docker-compose.yml` brings up Postgres+pgvector, Redis, both services, and the frontend with a single `docker compose up`. Given both services share the same Postgres instance and schema (Section 4 of the TDD), this is the biggest practical win for a 13-day sprint. | Compose file has to live somewhere — typically duplicated into both repos (drifts over time) or pulled into a third "infra" repo that each engineer also has to clone and keep in sync. Either way, one more moving part before anyone can run the stack locally. |
| **Team collaboration** | A change to the shared contract (e.g., adding a field to `InvestigationResult`) is one PR, one review, one CI run, one commit history entry — directly matches how TASK-19's contract test is scoped. Blast radius is visible in the diff. | The same change becomes two PRs that must land in a coordinated order (or a versioned contract package bumped in both), two reviews, and a real risk of the two repos drifting out of sync mid-sprint if one merges before the other. |
| **Team/security boundaries** | Weaker per-service access control — anyone with repo access can see and (with branch protection) propose changes to both services. Not a real constraint for one small team; would matter once separate teams with separate access needs own each service. | Clean boundary if/when a separate team, contractor, or open-source consumer needs access to only one service. |
| **Repo growth / build times at scale** | Non-issue at this scale (two services, one small vector-backed Postgres instance). Becomes a real concern only after years of history and many more services — not a 13-day-sprint problem. | No advantage yet, since there's no scale to protect against. |

## 3. Recommendation

**Use a single monorepo** with `/services/orchestrator` and `/services/engine` as top-level directories, following the pattern used by most small-to-mid-size teams building a decoupled-but-co-developed system (this is the same shape Google, Stripe, and most polyglot startups converge on before a service genuinely needs an independent team and release cadence — the "monorepo until it hurts" default).

**Why, specifically for TRACE:**

1. The internal REST contract between the two services is a first-class deliverable (TASK-19) with a CI-enforced contract test. That test is trivial in a monorepo (both codebases are already checked out together) and non-trivial across repos (needs a published spec package or a cross-repo CI trigger) — and non-trivial is exactly what a 13-day timeline can't absorb.
2. Both services depend on the same PostgreSQL/pgvector schema (Section 4.1–4.3 of the TDD). A schema migration that affects both the Java-owned tables and the Python-read `pgvector` tables is one PR and one migration file in a monorepo, not a coordinated two-repo change.
3. Local development needs one Postgres + Redis + two app containers running together to test anything end-to-end (the full sequence in Section 2 of the TDD touches both services on every request). One `docker-compose.yml` at the repo root removes an entire category of "works on my machine, but I forgot to pull the other repo's latest compose file" bugs during the sprint.
4. Maven/Gradle and Poetry/Pipenv genuinely don't interfere with each other in a monorepo — this is the factor multi-repo advocates usually lean on, and it doesn't apply pressure here.
5. Nothing about this recommendation blocks independent deployment. Each service still builds its own image and can be deployed, scaled, and rolled back independently — that's a property of the container boundary, not the repo boundary.

**When to revisit:** if TRACE grows to the point where the AI service and the orchestration service are owned by genuinely separate teams with independent release cadences and access-control needs, splitting is a low-cost extraction later (`git subtree split` or `git filter-repo` preserves history per directory) — because the two services already communicate only over HTTP with no shared code, there's no coupling to untangle at that point, only a directory to lift out.

## 4. Naming Conventions

### 4.1 If Monorepo (recommended)

- **Repository name:** `trace-platform` (kebab-case; avoids the bare `trace`, which is likely to collide with other org repos or tools named "trace")
- **Directories:** `orchestrator` and `engine` (kebab-case, function-first names rather than technology-first — keeps them stable if the underlying framework ever changes)

### 4.2 If Multi-repo (for reference, should the team's constraints change)

| Purpose | Repo name |
|---|---|
| Spring Boot Core | `trace-orchestrator` (or `trace-core`) |
| FastAPI AI Service | `trace-engine` (or `trace-ai-service`) |
| React/Next.js frontend | `trace-frontend` |
| Shared OpenAPI/contract definitions | `trace-contracts` (published as a versioned package both services pull — required to make TASK-19's contract test work across repos) |
| Local dev / Docker Compose / shared infra | `trace-infra` |

## 5. Recommended Directory Layout (Monorepo)

```
trace-platform/
├── services/
│   ├── orchestrator/                  # Spring Boot Core
│   │   ├── src/main/java/com/trace/...
│   │   ├── src/main/resources/
│   │   │   └── application.yml        # incl. resilience4j config (TDD §5.1)
│   │   ├── src/test/java/...
│   │   ├── pom.xml                    # or build.gradle.kts
│   │   └── Dockerfile
│   │
│   ├── engine/                        # FastAPI AI Service
│   │   ├── app/
│   │   │   ├── agents/                # orchestrator, investigation, policy, memory, action
│   │   │   ├── tools/                 # get_invoice, get_purchase_order, get_vendor, ...
│   │   │   ├── api/                   # /internal/v1/investigate, /internal/v1/memory/write-back
│   │   │   └── main.py
│   │   ├── tests/
│   │   ├── pyproject.toml             # or Pipfile
│   │   └── Dockerfile
│   │
│   └── frontend/                      # React (Next.js) — if kept in the same repo
│       ├── src/
│       └── package.json
│
├── contracts/                         # Shared source of truth for cross-service payloads
│   ├── openapi/
│   │   ├── client-api.yaml            # Client ↔ Spring Boot Core (TDD §3.1)
│   │   └── internal-api.yaml          # Spring Boot Core ↔ FastAPI (TDD §3.2)
│   └── schemas/
│       └── error-envelope.schema.json # TDD §3.4 standard error shape
│
├── infra/
│   ├── docker/
│   │   ├── docker-compose.yml         # postgres+pgvector, redis, orchestrator, engine, frontend
│   │   └── docker-compose.override.yml
│   ├── db/
│   │   └── migrations/                # Flyway/Liquibase DDL (TDD §4.1, §4.3)
│   └── k8s/                           # future — post-MVP deployment manifests
│
├── docs/
│   ├── architecture/                  # TRACE_Technical_Design_Document and diagrams
│   ├── decisions/                     # ADR log — e.g. 0001-repository-strategy.md (this document)
│   └── runbooks/                      # on-call / operational notes
│
├── .github/
│   └── workflows/
│       ├── orchestrator-ci.yml        # path filter: services/orchestrator/**
│       ├── engine-ci.yml              # path filter: services/engine/**
│       └── contract-tests.yml         # path filter: contracts/**, runs against both services
│
├── .gitignore
└── README.md
```

Two structural notes worth calling out:

- `contracts/` is a top-level directory, not nested under either service — it's the shared source both sides generate/validate clients against, and putting it outside `services/` makes the CI path-filter for `contract-tests.yml` unambiguous.
- `infra/db/migrations/` stays owned by Spring Boot Core in practice (per TDD §4.1, PostgreSQL is "owned by Spring Boot Core"), but living at the repo root rather than inside `services/orchestrator/` makes it clear the FastAPI service reads from (and pgvector tables inside) the same physical database, even though it doesn't own the migration process.
