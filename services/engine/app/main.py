"""
TRACE FastAPI AI Service — entry point.

Owns the multi-agent investigation engine (Orchestrator, Investigation,
Policy, Memory, and Action agents). See docs/architecture for the full
technical design and contracts/openapi/internal-api.yaml for the request/
response contract this service exposes to the Spring Boot Core.
"""

from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI

from app.api import investigate
from app.bootstrap import build_resources
from app.config import get_settings
from app.errors import register_exception_handlers
from app.tracing import TraceIdMiddleware, configure_logging

# The memory write-back router joins `investigate` in create_app() once implemented.


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()  # fail fast at startup if required config is missing
    configure_logging(settings.log_level)
    app.state.resources = build_resources(settings)
    try:
        yield
    finally:
        await app.state.resources.aclose()


def create_app() -> FastAPI:
    app = FastAPI(
        title="TRACE AI Service",
        description="Multi-agent investigation engine for the TRACE platform",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.add_middleware(TraceIdMiddleware)
    register_exception_handlers(app)
    app.include_router(investigate.router, prefix="/internal/v1")

    @app.get("/health")
    def health() -> dict:
        """Liveness endpoint — kept dependency-free for docker-compose healthchecks."""
        return {
            "status": "ok",
            "service": "engine",
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }

    return app


app = create_app()
