import asyncio
import os
import sys
import time
from pathlib import Path

# Must be set before app.config.get_settings() is first called.
TEST_JWT_SECRET = "test-secret-only-used-by-the-engine-test-suite"
os.environ.setdefault("INTERNAL_JWT_SECRET", TEST_JWT_SECRET)
os.environ.setdefault("DB_PASSWORD", "test-db-password")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-not-real")

import pytest  # noqa: E402
from fastapi import Depends  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from jose import jwt  # noqa: E402
from pydantic import BaseModel  # noqa: E402

from app.config import get_settings  # noqa: E402
from app.main import create_app  # noqa: E402
from app.security import verify_internal_jwt  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[3]
CONTRACTS_DIR = REPO_ROOT / "contracts"
AUDIENCE = "fastapi-ai-service"


class ProbeBody(BaseModel):
    name: str
    count: int


@pytest.fixture(scope="session")
def event_loop_policy():
    # psycopg's async mode can't run on Windows' default ProactorEventLoop.
    if sys.platform == "win32":
        return asyncio.WindowsSelectorEventLoopPolicy()
    return asyncio.DefaultEventLoopPolicy()


@pytest.fixture
def make_token():
    """Builds an internal JWT the way Spring Boot Core would; override any claim/secret."""

    def _make(secret: str | None = None, algorithm: str = "HS256", **claim_overrides) -> str:
        claims = {"aud": AUDIENCE, "exp": int(time.time()) + 300, "sub": "spring-boot-core"}
        claims.update(claim_overrides)
        claims = {k: v for k, v in claims.items() if v is not None}
        secret = secret or get_settings().internal_jwt_secret.get_secret_value()
        return jwt.encode(claims, secret, algorithm=algorithm)

    return _make


@pytest.fixture
def client():
    """App with throwaway probe routes: one protected + validated, one that blows up."""
    app = create_app()

    @app.post("/probe")
    def probe(body: ProbeBody, _claims: dict = Depends(verify_internal_jwt)) -> dict:
        return {"ok": True}

    @app.get("/boom")
    def boom() -> dict:
        raise RuntimeError("secret internal detail")

    # raise_server_exceptions=False: we want to assert on the 500 envelope
    # the catch-all handler returns, not have the test client re-raise.
    return TestClient(app, raise_server_exceptions=False)
