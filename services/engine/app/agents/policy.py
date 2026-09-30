"""
Policy Agent (TDD §2 step 7): finds the policy clauses most relevant to the
situation with a cosine-similarity search. It only retrieves; whether a clause
is actually violated is judged in the root-cause step, with the facts in hand.
"""

import logging
from dataclasses import dataclass

from app.agents.trace import TraceRecorder
from app.deadline import Deadline
from app.llm.client import LlmClient, LlmError
from app.tools.vector_store import PolicyClause, VectorStore

logger = logging.getLogger(__name__)
TOOL = "search_policy"


@dataclass(frozen=True)
class PolicyRetrieval:
    clauses: list[PolicyClause]


class PolicyAgent:
    def __init__(self, llm: LlmClient, vectors: VectorStore, top_k: int) -> None:
        self._llm = llm
        self._vectors = vectors
        self._top_k = top_k

    async def run(self, query: str, recorder: TraceRecorder, deadline: Deadline) -> PolicyRetrieval:
        tool_input = {"query": query, "top_k": self._top_k}
        try:
            (embedding,) = await self._llm.embed([query], task="query", deadline=deadline)
        except LlmError as exc:
            recorder.record("policy", "TOOL_CALL", TOOL, tool_input, {"error": str(exc)})
            raise
        clauses = await self._vectors.search_policy(embedding, self._top_k)
        recorder.record(
            "policy",
            "TOOL_CALL",
            TOOL,
            tool_input,
            {
                "matches": [
                    {"clause_id": c.clause_id, "similarity": round(c.similarity, 4)}
                    for c in clauses
                ]
            },
        )
        return PolicyRetrieval(clauses=clauses)
