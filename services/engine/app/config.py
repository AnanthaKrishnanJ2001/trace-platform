"""
Runtime configuration, read from environment variables (wired to
infra/docker/.env.example via docker-compose).

Only settings that are actually used belong here; add the rest (Gemini)
alongside the code that consumes them.
"""

from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import URL


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore")

    # Shared HS256 secret Spring Boot Core signs the internal JWT with. No
    # default on purpose: the service must refuse to start without one rather
    # than fall back to a value that's in source control. Same for the DB
    # password.
    internal_jwt_secret: SecretStr
    internal_jwt_audience: str = "fastapi-ai-service"

    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "trace"
    db_user: str = "trace"
    db_password: SecretStr

    redis_host: str = "localhost"
    redis_port: int = 6379

    # Gemini via the Gen AI SDK with an API key. Model IDs were chosen by
    # benchmarking latency/reliability against real calls (the bigger flash
    # models were too slow and flaky for the 8s budget); override via env.
    gemini_api_key: SecretStr = Field(min_length=1)  # empty means "not configured": fail at startup
    gemini_model: str = "gemini-3.5-flash-lite"
    gemini_fallback_model: str | None = "gemini-3.1-flash-lite"
    gemini_thinking_level: str | None = "minimal"
    # Vectors in the database are vector(768) (migration V2) and only comparable
    # with vectors from the same model: changing either means re-embedding.
    embedding_model: str = "gemini-embedding-2"
    embedding_dimensions: int = 768
    # Per model call, further capped by the time left. TDD §5.4 says 5s, but that can't
    # work inside a 7s budget: one hung primary call would leave the fallback ~1s and
    # failover would never succeed. Typical latency is ~1.5-2s (measured), so 3s keeps
    # room for the fallback to get a real attempt.
    llm_call_timeout_seconds: float = 3.0

    # Spring Boot Core gives up at 8s (TDD §5.4); finish inside it.
    investigation_deadline_seconds: float = 7.0
    default_tolerance_pct: float = 2.0  # TDD §3.2's value, used when the request has no context
    retrieval_top_k: int = 3
    precedent_min_similarity: float = 0.5

    log_level: str = "INFO"

    @property
    def database_url(self) -> URL:
        # URL.create escapes the password, so special characters are safe.
        return URL.create(
            "postgresql+psycopg",
            username=self.db_user,
            password=self.db_password.get_secret_value(),
            host=self.db_host,
            port=self.db_port,
            database=self.db_name,
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()
