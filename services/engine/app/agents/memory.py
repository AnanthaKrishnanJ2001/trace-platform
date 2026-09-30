"""
Memory Agent (TDD §2 step 8): finds past incidents most similar to the current
one, so a resolution that worked before can inform the recommendation.
"""

from dataclasses import dataclass

from app.agents.trace import TraceRecorder
from app.deadline import Deadline
from app.llm.client import LlmClient, LlmError
from app.tools.vector_store import IncidentMatch, VectorStore

TOOL = "search_previous_incidents"


@dataclass(frozen=True)
class MemoryRetrieval:
    matches: list[IncidentMatch]  # best first

    def relevant(self, min_similarity: float) -> list[IncidentMatch]:
        """Only the matches close enough to count as a precedent."""
        return [m for m in self.matches if m.similarity >= min_similarity]


class MemoryAgent:
    def __init__(self, llm: LlmClient, vectors: VectorStore, top_k: int) -> None:
        self._llm = llm
        self._vectors = vectors
        self._top_k = top_k

    async def run(self, query: str, recorder: TraceRecorder, deadline: Deadline) -> MemoryRetrieval:
        tool_input = {"query": query, "top_k": self._top_k}
        try:
            (embedding,) = await self._llm.embed([query], task="query", deadline=deadline)
        except LlmError as exc:
            recorder.record("memory", "TOOL_CALL", TOOL, tool_input, {"error": str(exc)})
            raise
        matches = await self._vectors.search_incidents(embedding, self._top_k)
        recorder.record(
            "memory",
            "TOOL_CALL",
            TOOL,
            tool_input,
            {
                "matches": [
                    {"incident_id": m.incident_id, "similarity": round(m.similarity, 4)}
                    for m in matches
                ]
            },
        )
        return MemoryRetrieval(matches=matches)
