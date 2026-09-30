"""
pgvector access for the Policy and Memory agents (TDD §4.3): cosine-similarity
search over policy_chunks and incident_embeddings, plus the writes the seeding
script and memory write-back need. These two tables are the only ones the
engine writes.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Protocol

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine

from app.db import fetch_rows

_SEARCH_POLICY_SQL = """
    SELECT document_name, clause_id, chunk_text,
           1 - (embedding <=> CAST(:e AS vector)) AS similarity
    FROM policy_chunks
    ORDER BY embedding <=> CAST(:e AS vector)
    LIMIT :k
"""
_SEARCH_INCIDENTS_SQL = """
    SELECT ie.incident_id, ie.summary_text, i.root_cause, i.resolution, i.decision, i.outcome,
           1 - (ie.embedding <=> CAST(:e AS vector)) AS similarity
    FROM incident_embeddings ie
    JOIN incidents i ON i.incident_id = ie.incident_id
    ORDER BY ie.embedding <=> CAST(:e AS vector)
    LIMIT :k
"""
_INCIDENTS_WITHOUT_EMBEDDING_SQL = """
    SELECT i.incident_id, i.root_cause, i.resolution, i.decision
    FROM incidents i
    LEFT JOIN incident_embeddings ie ON ie.incident_id = i.incident_id
    WHERE ie.incident_id IS NULL
    ORDER BY i.incident_id
"""


@dataclass(frozen=True)
class PolicyClause:
    document_name: str
    clause_id: str | None
    text: str
    similarity: float


@dataclass(frozen=True)
class IncidentMatch:
    incident_id: str
    similarity: float
    root_cause: str
    resolution: str
    decision: str | None
    outcome: str
    summary_text: str


@dataclass(frozen=True)
class PolicyChunkToStore:
    clause_id: str
    text: str
    embedding: Sequence[float]


class VectorStore(Protocol):
    async def search_policy(self, embedding: Sequence[float], top_k: int) -> list[PolicyClause]: ...

    async def search_incidents(
        self, embedding: Sequence[float], top_k: int
    ) -> list[IncidentMatch]: ...


def to_vector_literal(embedding: Sequence[float]) -> str:
    """pgvector's text form, e.g. '[0.1,0.2]'; bound as a parameter and CAST in SQL."""
    return "[" + ",".join(repr(float(x)) for x in embedding) + "]"


class PostgresVectorStore:
    def __init__(self, engine: AsyncEngine) -> None:
        self._engine = engine

    async def search_policy(self, embedding: Sequence[float], top_k: int) -> list[PolicyClause]:
        rows = await fetch_rows(
            self._engine, _SEARCH_POLICY_SQL, {"e": to_vector_literal(embedding), "k": top_k}
        )
        return [
            PolicyClause(
                document_name=r["document_name"],
                clause_id=r["clause_id"],
                text=r["chunk_text"],
                similarity=float(r["similarity"]),
            )
            for r in rows
        ]

    async def search_incidents(self, embedding: Sequence[float], top_k: int) -> list[IncidentMatch]:
        rows = await fetch_rows(
            self._engine, _SEARCH_INCIDENTS_SQL, {"e": to_vector_literal(embedding), "k": top_k}
        )
        return [
            IncidentMatch(
                incident_id=r["incident_id"],
                similarity=float(r["similarity"]),
                root_cause=r["root_cause"],
                resolution=r["resolution"],
                decision=r["decision"],
                outcome=r["outcome"],
                summary_text=r["summary_text"],
            )
            for r in rows
        ]

    # ---- writes (seeding, memory write-back) ----

    async def incidents_without_embeddings(self) -> list[Mapping[str, Any]]:
        return await fetch_rows(self._engine, _INCIDENTS_WITHOUT_EMBEDDING_SQL, {})

    async def replace_policy_document(
        self, document_name: str, chunks: Sequence[PolicyChunkToStore]
    ) -> int:
        """Swaps a document's chunks for a new set atomically, so a re-run never duplicates."""
        async with self._engine.begin() as conn:
            await conn.execute(
                text("DELETE FROM policy_chunks WHERE document_name = :d"), {"d": document_name}
            )
            if chunks:
                await conn.execute(
                    text(
                        "INSERT INTO policy_chunks (document_name, clause_id, chunk_text, embedding) "
                        "VALUES (:d, :c, :t, CAST(:e AS vector))"
                    ),
                    [
                        {
                            "d": document_name,
                            "c": chunk.clause_id,
                            "t": chunk.text,
                            "e": to_vector_literal(chunk.embedding),
                        }
                        for chunk in chunks
                    ],
                )
        return len(chunks)

    async def upsert_incident_embedding(
        self, incident_id: str, summary_text: str, embedding: Sequence[float]
    ) -> None:
        async with self._engine.begin() as conn:
            await conn.execute(
                text(
                    "INSERT INTO incident_embeddings (incident_id, summary_text, embedding) "
                    "VALUES (:i, :s, CAST(:e AS vector)) "
                    "ON CONFLICT (incident_id) DO UPDATE "
                    "SET summary_text = EXCLUDED.summary_text, embedding = EXCLUDED.embedding"
                ),
                {"i": incident_id, "s": summary_text, "e": to_vector_literal(embedding)},
            )
