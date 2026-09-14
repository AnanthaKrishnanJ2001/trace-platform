# trace-platform
Transactional Root-cause Analysis &amp; Context Engine Technical Architecture Specification
# TRACE — Transactional Root-cause Analysis & Context Engine

Decoupled dual-service architecture: **Spring Boot Core** owns orchestration, persistence, and audit; the **FastAPI AI Service** hosts the multi-agent investigation engine. The two communicate only over a versioned internal REST contract — see [`docs/decisions/0001-repository-strategy.md`](docs/decisions/0001-repository-strategy.md) for why this lives in one repository, and `docs/architecture/` for the full technical design.

## Repository layout

```
trace-platform/
├── services/
│   ├── orchestrator/     # Spring Boot Core (Java 21, Maven)
│   ├── engine/            # FastAPI AI Service (Python 3.12, Poetry)
│   └── frontend/          # React/Next.js UI (reserved — not yet scaffolded)
├── contracts/             # Shared OpenAPI specs and JSON schemas
├── infra/
│   ├── docker/            # docker-compose.yml + env templates for local dev
│   ├── db/migrations/     # Flyway/Liquibase DDL (owned by orchestrator)
│   └── k8s/               # reserved for post-MVP deployment manifests
└── docs/
    ├── architecture/      # Technical design docs
    ├── decisions/         # ADR log
    └── runbooks/          # Operational notes
```

## Prerequisites

- Java 21 (Temurin recommended) + Maven 3.9+
- Python 3.12+ with [Poetry](https://python-poetry.org/)
- Docker + Docker Compose v2

## Local development

1. Copy the environment template and fill in real values:
   ```bash
   cp infra/docker/.env.example infra/docker/.env
   ```
2. Bring up the full stack (Postgres + pgvector, Redis, both services):
   ```bash
   docker compose --env-file infra/docker/.env -f infra/docker/docker-compose.yml up --build
   ```
3. Health checks:
   - Spring Boot Core: `http://localhost:8080/actuator/health`
   - FastAPI AI Service: `http://localhost:8000/health`

## Running services individually

**Orchestrator (Spring Boot):**
```bash
cd services/orchestrator
./mvnw spring-boot:run
```

**Engine (FastAPI):**
```bash
cd services/engine
poetry install
poetry run uvicorn app.main:app --reload --port 8000
```

## Contracts

The internal REST contract between the two services (and the client-facing API) is specified under `contracts/openapi/`. Both services should be validated against these specs in CI once the contract-test suite (backlog TASK-19) is implemented — treat this directory as the source of truth for request/response shapes, not either service's code.

## Documentation

- `docs/architecture/` — the TRACE Technical Design Document and supporting diagrams
- `docs/decisions/` — Architecture Decision Records (ADRs), starting with the repository strategy decision
- `docs/runbooks/` — operational and on-call notes (populated as the system nears production)
