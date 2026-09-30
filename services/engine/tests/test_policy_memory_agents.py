import dataclasses

import pytest

from app.agents.memory import MemoryAgent, MemoryRetrieval
from app.agents.policy import PolicyAgent
from app.agents.trace import TraceRecorder
from app.db import DataAccessError
from app.deadline import Deadline
from app.llm.client import LlmUnavailable
from tests.fakes import CLAUSE_42, CLAUSE_43, INC_8721, FakeLlm, FakeVectorStore


async def test_policy_agent_embeds_the_query_as_a_query_and_searches_top_k():
    llm, vectors, recorder = (
        FakeLlm(),
        FakeVectorStore(clauses=[CLAUSE_42, CLAUSE_43]),
        TraceRecorder(),
    )
    result = await PolicyAgent(llm, vectors, top_k=1).run("the query", recorder, Deadline(7))

    assert llm.embed_calls == [(["the query"], "query")]
    assert vectors.calls == [("search_policy", 1)]
    assert [c.clause_id for c in result.clauses] == ["IVP-4.2"]
    (step,) = recorder.steps
    assert (step.agent, step.step, step.tool) == ("policy", "TOOL_CALL", "search_policy")
    assert step.tool_input == {"query": "the query", "top_k": 1}
    assert step.tool_output == {"matches": [{"clause_id": "IVP-4.2", "similarity": 0.83}]}


async def test_memory_agent_traces_the_incidents_it_found():
    recorder = TraceRecorder()
    result = await MemoryAgent(FakeLlm(), FakeVectorStore(incidents=[INC_8721]), top_k=3).run(
        "q", recorder, Deadline(7)
    )
    assert [m.incident_id for m in result.matches] == ["INC-8721"]
    (step,) = recorder.steps
    assert (step.agent, step.tool) == ("memory", "search_previous_incidents")
    assert step.tool_output == {"matches": [{"incident_id": "INC-8721", "similarity": 0.71}]}


@pytest.mark.parametrize(
    ("agent_cls", "agent_name"), [(PolicyAgent, "policy"), (MemoryAgent, "memory")]
)
async def test_an_embedding_failure_is_traced_then_raised(agent_cls, agent_name):
    recorder = TraceRecorder()
    agent = agent_cls(FakeLlm(embed_error=LlmUnavailable("model overloaded")), FakeVectorStore(), 3)
    with pytest.raises(LlmUnavailable):
        await agent.run("q", recorder, Deadline(7))
    (step,) = recorder.steps
    assert step.agent == agent_name
    assert step.tool_output == {
        "error": "model overloaded"
    }  # the audit trail shows why it degraded


@pytest.mark.parametrize("agent_cls", [PolicyAgent, MemoryAgent])
async def test_a_database_failure_is_not_swallowed(agent_cls):
    agent = agent_cls(FakeLlm(), FakeVectorStore(error=DataAccessError("db down")), 3)
    with pytest.raises(DataAccessError):
        await agent.run("q", TraceRecorder(), Deadline(7))


def test_only_matches_above_the_threshold_count_as_precedents():
    weak = dataclasses.replace(INC_8721, incident_id="INC-1", similarity=0.30)
    retrieval = MemoryRetrieval(matches=[INC_8721, weak])
    assert [m.incident_id for m in retrieval.relevant(0.5)] == ["INC-8721"]
    assert retrieval.relevant(0.9) == []
