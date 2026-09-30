"""
Embeds the knowledge the Policy and Memory agents search, and loads it into
pgvector: every clause of the invoice validation policy, and every resolved
incident that doesn't have an embedding yet. Safe to re-run.

    python -m app.scripts.seed_knowledge

Needs the same environment as the service (database, Gemini key).
"""

import asyncio
import logging
import sys
from pathlib import Path

from sqlalchemy.ext.asyncio import AsyncEngine

from app.config import get_settings
from app.deadline import Deadline
from app.db import create_db_engine
from app.knowledge.incidents import incident_summary_text
from app.knowledge.policy_parser import parse_policy
from app.llm.client import LlmClient
from app.llm.gemini import GeminiClient
from app.tools.vector_store import PolicyChunkToStore, PostgresVectorStore
from app.tracing import configure_logging

logger = logging.getLogger("seed_knowledge")

POLICY_FILE = Path(__file__).resolve().parent.parent / "knowledge" / "invoice_validation_policy.md"
SEED_TIME_BUDGET_SECONDS = 60
# Seeding is offline and embeds many texts per call, so it gets far more time than a request does.
SEED_CALL_TIMEOUT_SECONDS = 30


async def seed(engine: AsyncEngine, llm: LlmClient) -> dict[str, int]:
    store = PostgresVectorStore(engine)
    deadline = Deadline(SEED_TIME_BUDGET_SECONDS)

    document = parse_policy(POLICY_FILE.read_text(encoding="utf-8"))
    vectors = await llm.embed(
        [clause.embedding_text for clause in document.clauses], task="document", deadline=deadline
    )
    chunks = [
        PolicyChunkToStore(clause_id=c.clause_id, text=c.text, embedding=v)
        for c, v in zip(document.clauses, vectors, strict=True)
    ]
    policy_count = await store.replace_policy_document(document.name, chunks)
    logger.info("Loaded %d clauses of %r", policy_count, document.name)

    pending = await store.incidents_without_embeddings()
    if pending:
        summaries = [
            incident_summary_text(r["root_cause"], r["resolution"], r["decision"]) for r in pending
        ]
        incident_vectors = await llm.embed(summaries, task="document", deadline=deadline)
        for row, summary, vector in zip(pending, summaries, incident_vectors, strict=True):
            await store.upsert_incident_embedding(row["incident_id"], summary, vector)
    logger.info("Embedded %d incident(s)", len(pending))
    return {"policy_clauses": policy_count, "incidents": len(pending)}


async def main() -> None:
    settings = get_settings()
    configure_logging(settings.log_level)
    engine = create_db_engine(settings.database_url)
    try:
        llm = GeminiClient.from_settings(settings, call_timeout_seconds=SEED_CALL_TIMEOUT_SECONDS)
        result = await seed(engine, llm)
    finally:
        await engine.dispose()
    print(f"seeded: {result}")


if __name__ == "__main__":
    if sys.platform == "win32":  # psycopg async can't use the default Proactor loop
        asyncio.run(main(), loop_factory=asyncio.SelectorEventLoop)
    else:
        asyncio.run(main())
