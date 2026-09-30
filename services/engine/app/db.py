"""
PostgreSQL access. The database is Spring Boot Core's (schema and migrations
live in infra/db/migrations); the engine only reads its tables, and writes
only policy_chunks / incident_embeddings.
"""

import logging
from collections.abc import Mapping
from typing import Any

from sqlalchemy import URL, text
from sqlalchemy.exc import OperationalError
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

logger = logging.getLogger(__name__)

# TDD §5.4: FastAPI -> PostgreSQL/pgvector is 2s per query, 1 retry.
QUERY_TIMEOUT_MS = 2000
CONNECT_TIMEOUT_SECONDS = 2
MAX_ATTEMPTS = 2


class DataAccessError(Exception):
    """A database call still failed after its retry. Surfaces to callers as a 500 (TDD §5.4)."""


def create_db_engine(url: URL | str) -> AsyncEngine:
    return create_async_engine(
        url,
        pool_size=5,
        max_overflow=5,
        pool_pre_ping=True,
        connect_args={
            "connect_timeout": CONNECT_TIMEOUT_SECONDS,
            # Enforced by the server, so a slow query is cancelled cleanly
            # instead of leaving a half-read connection in the pool.
            "options": f"-c statement_timeout={QUERY_TIMEOUT_MS}",
        },
    )


async def fetch_rows(
    engine: AsyncEngine, sql: str, params: dict[str, Any]
) -> list[Mapping[str, Any]]:
    """Runs a read query with the engine's timeout and one retry (TDD §5.4)."""
    last_error: OperationalError | None = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            async with engine.connect() as conn:
                result = await conn.execute(text(sql), params)
                return list(result.mappings().all())
        except OperationalError as exc:
            # Connection failure or the server-side statement timeout.
            last_error = exc
            logger.warning(
                "Database read failed (attempt %d/%d): %s",
                attempt,
                MAX_ATTEMPTS,
                type(exc.orig).__name__,
            )
    raise DataAccessError("Database read failed after retry") from last_error
