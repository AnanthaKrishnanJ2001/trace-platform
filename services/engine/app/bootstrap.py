"""Builds the engine's long-lived objects from Settings; closed on shutdown."""

from dataclasses import dataclass

from redis.asyncio import Redis
from sqlalchemy.ext.asyncio import AsyncEngine

from app.agents.investigation import InvestigationAgent
from app.agents.memory import MemoryAgent
from app.agents.orchestrator import OrchestratorAgent
from app.agents.policy import PolicyAgent
from app.agents.synthesis import RootCauseSynthesizer
from app.cache import RedisCache, create_redis
from app.config import Settings
from app.db import create_db_engine
from app.llm.gemini import GeminiClient
from app.tools.reference_data import ReferenceDataTools
from app.tools.store import PostgresReferenceStore
from app.tools.vector_store import PostgresVectorStore


@dataclass
class Resources:
    engine: AsyncEngine
    redis: Redis
    orchestrator: OrchestratorAgent

    async def aclose(self) -> None:
        await self.redis.aclose()
        await self.engine.dispose()


def build_resources(settings: Settings) -> Resources:
    engine = create_db_engine(settings.database_url)
    redis = create_redis(settings.redis_host, settings.redis_port)
    llm = GeminiClient.from_settings(settings)
    vectors = PostgresVectorStore(engine)
    tools = ReferenceDataTools(PostgresReferenceStore(engine), RedisCache(redis))
    orchestrator = OrchestratorAgent(
        InvestigationAgent(tools),
        PolicyAgent(llm, vectors, settings.retrieval_top_k),
        MemoryAgent(llm, vectors, settings.retrieval_top_k),
        RootCauseSynthesizer(llm),
        default_tolerance_pct=settings.default_tolerance_pct,
        deadline_seconds=settings.investigation_deadline_seconds,
        precedent_min_similarity=settings.precedent_min_similarity,
    )
    return Resources(engine=engine, redis=redis, orchestrator=orchestrator)
