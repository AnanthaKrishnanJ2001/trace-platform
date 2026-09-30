# Engine (FastAPI AI Service) — Rules for Claude Code

Scope: everything under `services/engine/`. Loaded on top of the repo-wide rules
in the root `CLAUDE.md` (contracts-first, error envelope, `trace_id`, internal JWT).

## Tech stack (pinned in `pyproject.toml` / `poetry.lock` — don't silently change)

- Python 3.12, FastAPI, Pydantic v2, Poetry (lock file is committed and used by the Dockerfile)
- Google Gen AI SDK (`google-genai`) for Gemini — Vertex AI or AI Studio key, chosen by config
- Persistence: SQLAlchemy + psycopg 3 against the shared PostgreSQL; pgvector for embeddings
- Cache: Redis, cache-aside (TDD §4.2)
- Test: pytest (+ pytest-asyncio, `asyncio_mode = "auto"`); lint/format: ruff (line length 100)

## Layout

```
app/
├── main.py         # create_app(): middleware, handlers, routers. `app = create_app()`
├── config.py       # Settings from env; add a setting only alongside the code that uses it
├── tracing.py      # X-Trace-Id middleware, log filter, bind_trace_id()
├── errors.py       # ErrorEnvelope + ApiError + exception handlers (the ONLY way errors leave)
├── security.py     # verify_internal_jwt dependency
├── types.py        # Money (Decimal in, JSON number out)
├── deadline.py     # Deadline: the time budget shared by every slow step of one investigation
├── bootstrap.py    # builds DB/Redis/Gemini/agents from Settings (lifespan); closed on shutdown
├── db.py           # async engine factory (2s timeout), fetch_rows (1 retry), DataAccessError
├── cache.py        # Cache protocol + best-effort RedisCache (200ms timeouts)
├── llm/            # client.py: LlmClient protocol + errors; gemini.py: the only Gemini SDK user
├── api/            # investigate.py router, deps.py, schemas.py (models mirroring the contract)
├── agents/         # orchestrator (PLAN -> investigation -> policy||memory -> synthesis),
│                   # investigation, policy, memory (retrieval only), synthesis (the ONE model
│                   # call), rootcause (deterministic label/confidence/fallback), trace.py
├── knowledge/      # invoice_validation_policy.md (demo policy), policy_parser, incident summaries
├── scripts/        # seed_knowledge (embeds policy + incidents), dev_token (local JWT)
└── tools/          # records.py, store.py (Postgres), reference_data.py (+cache-aside),
                    # vector_store.py (pgvector search + the two tables the engine writes)
tests/              # mirrors app/; fakes.py = in-memory store/cache/LLM/vector store + demo data
```

### How an investigation runs (`POST /internal/v1/investigate`)

1. **Investigation Agent** (deterministic): invoice/PO/vendor lookups, exact `Decimal` amount
   comparison against the tolerance. The root-cause **label** comes from this, never from the model.
2. **Policy** and **Memory** agents run in parallel: embed one situation text, cosine-search
   pgvector. Retrieval only. A precedent counts only if its root cause equals the label.
3. **One model call** (`synthesis.py`): picks the governing policy clause, whether it is violated,
   the action (from a closed set) and the reason. Clause ids it cites are validated.
4. **Confidence** is computed from evidence (`rootcause.score_confidence`), not by the model.
5. Any model/embedding failure marks that section `INCOMPLETE` and the result `PARTIAL`; label,
   confidence and a rule-based recommendation are still returned. A missing invoice/PO/vendor is
   `FAILED`. A database failure is a 500 (TDD §5.4).

### Lessons from running against the real Gemini API

- **`gemini-embedding-2` merges a plain list of strings into ONE vector.** Always pass one
  `types.Content` per text (`GeminiClient.embed` does); the response shape is validated.
- The SDK lets raw `httpx` transport errors escape (e.g. "server disconnected"); they are
  handled as transient, like timeouts. Never let one become a 500.
- Generation latency is spiky (typically 1.5-2s, sometimes >5s or 503/429; bigger flash models were
  worse). A per-call cap of 3s and a fallback model keep a hung primary from eating the whole 7s
  budget. `tests/test_config.py` guards that invariant.
- Prompt wording changes behaviour: check clause selection live for BOTH a mismatch and a
  within-tolerance case after touching `synthesis.py` (tests only pin the wording, not the model).

Routers are thin: validate, delegate to an agent/service, map the result. No
business logic in `api/`.

## Rules specific to this service

- **Contract first.** `app/api/schemas.py` mirrors `contracts/openapi/internal-api.yaml`.
  Change the YAML first; `tests/test_contract.py` fails if the two drift. Respond with
  `response_model_exclude_none=True` so absent optional fields are omitted.
- **Every `/internal/v1/*` route depends on `verify_internal_jwt`.** HS256 is pinned and
  audience is checked; never log a token or the secret (log the failure class only).
  `/health` is the only unauthenticated route.
- **Errors:** raise `ApiError` or let the registered handlers map the failure. Never
  return `JSONResponse` error bodies by hand, and never put input values or exception
  text in a response (only field paths and generic messages).
- **`trace_id`** comes from the `X-Trace-Id` request header via `TraceIdMiddleware`; never
  regenerate it. Log through `logging` (the id is added automatically). Code that runs
  outside a request (scheduled jobs) or outside the middleware must use `bind_trace_id`.
- **Money is `Decimal`** everywhere inside the engine; use the `Money` type in models so it
  serializes as a JSON number. Never do arithmetic on floats.
- **Latency budget.** Spring Boot Core times out at 8s (TDD §5.4). Keep tool calls
  deterministic, run Policy and Memory lookups concurrently, and keep to ONE generation call per
  investigation. Every model call is capped by the time left in the `Deadline` (7s total) and
  falls back to a second model rather than retrying the same one (TDD §5.4's "5s x 2 retries"
  can't fit in 8s). A slow or failed agent yields an `INCOMPLETE` findings section and overall
  `PARTIAL`, not an exception.
- **Confidence is computed from evidence signals**, not taken from the model's own claim.
- **Database:** schema and migrations belong to the orchestrator's Flyway
  (`infra/db/migrations/`). The engine never runs DDL. It only writes `policy_chunks` and
  `incident_embeddings`; every other table is read-only. Embedding dimension must match
  the `vector(768)` columns in V2.
- All Gemini calls go through one client wrapper so timeouts, retries and test fakes live
  in one place. Tests never call the real API.

## Commands (from `services/engine/`)

```
poetry install --no-root      # or use the local .venv (gitignored)
python -m pytest              # unit tests; integration tests skip themselves
ruff check . && ruff format . # lint + format
```

Integration tests (`-m integration`) need a real Postgres with `V1`/`V2` migrations and
`infra/db/seed/demo_seed.sql` applied, plus Redis. Point them at throwaway containers —
they only ever delete their own cache keys, but don't aim them at a shared instance:

```
TEST_DATABASE_URL=postgresql+psycopg://user:pass@host:port/trace
TEST_REDIS_URL=redis://host:port/0
```

Local demo data: `infra/db/seed/demo_seed.sql` (see its header for the psql command).

### Run it end to end (real Gemini)

Needs `GEMINI_API_KEY` in `infra/docker/.env` (gitignored; `.env.example` has an empty placeholder).
Use a separate compose project so the demo database never collides with a real dev stack:

```
DC="docker compose -p trace-demo -f infra/docker/docker-compose.yml"
$DC up -d --build postgres redis engine
for f in V1__baseline_schema V2__vector_tables; do $DC exec -T postgres psql -U trace -d trace -q < infra/db/migrations/$f.sql; done
$DC exec -T postgres psql -U trace -d trace -q < infra/db/seed/demo_seed.sql
$DC exec -T engine python -m app.scripts.seed_knowledge      # embeds the policy + incidents
TOKEN=$($DC exec -T engine python -m app.scripts.dev_token)  # 5-minute internal JWT
curl -X POST localhost:8000/internal/v1/investigate -H "Authorization: Bearer $TOKEN"   -H "X-Trace-Id: demo-1" -H "Content-Type: application/json" -d @request.json
$DC down -v                                                   # stop and delete the demo data
```

(Spring's Flyway normally applies the migrations; the psql lines stand in for it. Don't point a
real orchestrator at this database afterwards: it has no Flyway history table.)

Windows: psycopg's async mode can't run on the default `ProactorEventLoop`, so run the
service via `docker compose` (Linux). The test suite selects the selector loop itself
(`event_loop_policy` in `tests/conftest.py`). `DB_PASSWORD` and `INTERNAL_JWT_SECRET` have
no defaults; compose supplies both.

## Redis keys are shared with Spring Boot Core

`po:{id}` (30 min) and `vendor:{id}` (1 h) are TDD §4.2's keys, and Spring is expected to
`DEL` them when it changes the row — so don't rename them. The engine caches plain JSON
objects there. Reads are defensive: anything unparseable or not our shape is a miss and is
left untouched, never overwritten. Before Spring starts writing these keys, agree the value
format with it (it currently has no Redis code at all).

## Anti-patterns — don't do these

- No new endpoint or field that isn't in `contracts/openapi/internal-api.yaml` first.
- No hardcoded credentials, project IDs or model secrets; read them from `Settings`.
- No `BaseHTTPMiddleware` (breaks contextvars); write pure ASGI middleware.
- No swallowing exceptions in agents to "keep going" without recording an `INCOMPLETE`
  section and an `agent_trace` step explaining why.
