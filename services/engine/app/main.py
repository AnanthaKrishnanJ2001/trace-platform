"""
TRACE FastAPI AI Service — entry point.

Owns the multi-agent investigation engine (Orchestrator, Investigation,
Policy, Memory, and Action agents). See docs/architecture for the full
technical design and contracts/openapi/internal-api.yaml for the request/
response contract this service exposes to the Spring Boot Core.
"""

from datetime import datetime, timezone

from fastapi import FastAPI

# Routers get registered here as they're implemented, e.g.:
# from app.api import investigate, memory
# app.include_router(investigate.router, prefix="/internal/v1")
# app.include_router(memory.router, prefix="/internal/v1")

app = FastAPI(
    title="TRACE AI Service",
    description="Multi-agent investigation engine for the TRACE platform",
    version="0.1.0",
)


@app.get("/health")
def health() -> dict:
    """Liveness endpoint — kept dependency-free for docker-compose healthchecks."""
    return {
        "status": "ok",
        "service": "engine",
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
