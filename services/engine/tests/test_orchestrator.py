import dataclasses
from decimal import Decimal

import pytest

from app.agents.investigation import InvestigationAgent, analyze_amounts
from app.agents.memory import MemoryAgent
from app.agents.orchestrator import OrchestratorAgent, describe_situation
from app.agents.policy import PolicyAgent
from app.agents.synthesis import RootCauseSynthesizer
from app.api.schemas import InvestigationContext, InvestigationRequest, References
from app.db import DataAccessError
from app.llm.client import LlmRequestError, LlmUnavailable
from app.tools.reference_data import ReferenceDataTools
from tests.fakes import (
    CLAUSE_42,
    CLAUSE_43,
    INC_8721,
    SYN_MISMATCH,
    SYN_TOLERANCE,
    FakeCache,
    FakeLlm,
    FakeVectorStore,
    demo_store,
)
from tests.test_contract import validate_against_contract

DEMO = References(invoice_id="INV-10492", po_id="PO-7821", vendor_id="VEND-3391")
SMALL = References(invoice_id="INV-10493", po_id="PO-7822", vendor_id="VEND-4410")


def request(refs=DEMO, tolerance=None, exception_type="PO_INVOICE_MISMATCH"):
    context = InvestigationContext(tolerance_pct=tolerance) if tolerance is not None else None
    return InvestigationRequest(
        investigation_id="INV-2026-000001",
        exception_id="EXC-1",
        exception_type=exception_type,
        references=refs,
        context=context,
    )


def build(llm=None, vectors=None, store=None):
    llm = llm if llm is not None else FakeLlm(synthesis=SYN_MISMATCH)
    vectors = (
        vectors if vectors is not None else FakeVectorStore([CLAUSE_42, CLAUSE_43], [INC_8721])
    )
    store = store or demo_store()
    orchestrator = OrchestratorAgent(
        InvestigationAgent(ReferenceDataTools(store, FakeCache())),
        PolicyAgent(llm, vectors, top_k=3),
        MemoryAgent(llm, vectors, top_k=3),
        RootCauseSynthesizer(llm),
        default_tolerance_pct=2.0,
        deadline_seconds=7.0,
        precedent_min_similarity=0.5,
    )
    return orchestrator, llm, vectors, store


def as_contract_json(result):
    emitted = result.model_dump(mode="json", exclude_none=True)
    validate_against_contract("InvestigationResult", emitted)
    return emitted


async def test_demo_case_completes_with_every_section():
    orchestrator, llm, _, _ = build()
    result = await orchestrator.investigate(request())
    body = as_contract_json(result)

    assert result.status == "COMPLETED"
    assert result.investigation_id == "INV-2026-000001"
    assert body["root_cause"]["label"] == "PO_INVOICE_AMOUNT_MISMATCH"
    # 40 base + 30 decisive + 15 policy agrees + round(15 * 0.71) precedent
    assert body["root_cause"]["confidence"] == 96
    assert body["root_cause"]["evidence"] == [
        {"source": "invoice", "field": "amount", "value": 52000.0},
        {"source": "purchase_order", "field": "approved_amount", "value": 45000.0},
    ]
    findings = body["findings"]
    assert findings["investigation_agent"]["difference"] == 7000.0
    assert findings["policy_agent"] == {
        "status": "OK",
        "violated": True,
        "policy_document": "Invoice Validation Policy",
        "clause_id": "IVP-4.2",
        "clause_text": CLAUSE_42.text,
    }
    assert findings["memory_agent"]["matches_found"] == 1
    assert findings["memory_agent"]["top_match"]["incident_id"] == "INC-8721"
    assert body["recommendation"] == {
        "action_type": "REQUEST_CORRECTED_INVOICE",
        "reason": SYN_MISMATCH.action_reason,
        "requires_approval": True,
    }
    assert len(llm.prompts) == 1  # exactly one generation call per investigation


async def test_trace_records_the_whole_run_in_order():
    orchestrator, *_ = build()
    steps = (await orchestrator.investigate(request())).agent_trace

    assert (steps[0].agent, steps[0].step) == ("orchestrator", "PLAN")
    assert (steps[-1].agent, steps[-1].step) == ("orchestrator", "AGGREGATE")
    tools = [s.tool for s in steps if s.tool]
    assert set(tools) == {
        "get_invoice",
        "get_purchase_order",
        "get_vendor",
        "search_policy",
        "search_previous_incidents",
    }
    aggregate = steps[-1].tool_output
    assert aggregate["synthesis"] == "llm" and aggregate["degraded"] == []
    assert aggregate["reasoning"] == SYN_MISMATCH.reasoning
    timestamps = [s.timestamp for s in steps]
    assert timestamps == sorted(timestamps)


async def test_within_tolerance_case():
    orchestrator, *_ = build(llm=FakeLlm(synthesis=SYN_TOLERANCE))
    result = await orchestrator.investigate(request(SMALL))
    body = as_contract_json(result)
    assert result.status == "COMPLETED"
    assert body["root_cause"]["label"] == "WITHIN_TOLERANCE"
    assert body["recommendation"]["action_type"] == "APPROVE_WITHIN_TOLERANCE"
    assert body["findings"]["policy_agent"]["violated"] is False
    assert body["findings"]["policy_agent"]["clause_id"] == "IVP-4.3"


async def test_the_requests_tolerance_overrides_the_default():
    orchestrator, *_ = build()
    default = await orchestrator.investigate(request(SMALL))
    strict = await orchestrator.investigate(request(SMALL, tolerance=1.0))
    assert default.root_cause.label == "WITHIN_TOLERANCE"
    assert strict.root_cause.label == "PO_INVOICE_AMOUNT_MISMATCH"  # 1.11% > 1%


async def test_weak_matches_are_not_precedents():
    weak = dataclasses.replace(INC_8721, similarity=0.30)
    orchestrator, *_ = build(vectors=FakeVectorStore([CLAUSE_42], [weak]))
    result = await orchestrator.investigate(request())
    memory = result.findings.memory_agent
    assert (memory.status, memory.matches_found, memory.top_match) == ("OK", 0, None)
    assert result.root_cause.confidence == 85  # no precedent points
    assert result.status == "COMPLETED"


async def test_a_similar_incident_with_a_different_root_cause_is_not_a_precedent():
    # Live run: INC-8721 (an amount mismatch) scored 0.82 against a within-tolerance invoice.
    orchestrator, llm, *_ = build(llm=FakeLlm(synthesis=SYN_TOLERANCE))
    result = await orchestrator.investigate(request(SMALL))
    memory = result.findings.memory_agent
    assert (memory.status, memory.matches_found, memory.top_match) == ("OK", 0, None)
    assert "no similar past incidents" in llm.prompts[0]


# ---- degradation ----


async def test_model_failure_falls_back_to_rules_and_marks_the_result_partial():
    orchestrator, *_ = build(llm=FakeLlm(generate_error=LlmUnavailable("model overloaded")))
    result = await orchestrator.investigate(request())
    body = as_contract_json(result)

    assert result.status == "PARTIAL"
    assert body["root_cause"]["label"] == "PO_INVOICE_AMOUNT_MISMATCH"  # still determined
    assert body["recommendation"]["action_type"] == "REQUEST_CORRECTED_INVOICE"  # from the rule
    assert body["recommendation"]["requires_approval"] is True
    policy = body["findings"]["policy_agent"]
    assert policy["status"] == "INCOMPLETE" and "model overloaded" in policy["reason"]
    assert body["findings"]["memory_agent"]["status"] == "OK"  # retrieval still worked
    assert body["root_cause"]["confidence"] == 81  # 40 + 30 + precedent 11; no policy points
    last = result.agent_trace[-1].tool_output
    assert last["synthesis"] == "fallback" and last["degraded"] == ["policy", "synthesis"]


async def test_embedding_outage_degrades_both_retrievals_but_still_answers():
    llm = FakeLlm(synthesis=SYN_MISMATCH, embed_error=LlmUnavailable("embedding timed out"))
    orchestrator, *_ = build(llm=llm)
    result = await orchestrator.investigate(request())
    body = as_contract_json(result)

    assert result.status == "PARTIAL"
    assert body["findings"]["policy_agent"]["status"] == "INCOMPLETE"
    assert body["findings"]["memory_agent"]["status"] == "INCOMPLETE"
    # The model still got the facts, so it still recommends; no clause was retrievable.
    assert len(llm.prompts) == 1 and "no policy clauses were retrieved" in llm.prompts[0]
    errors = [
        s.tool_output for s in result.agent_trace if s.tool_output and "error" in s.tool_output
    ]
    assert len(errors) == 2


async def test_a_model_citing_an_unknown_clause_is_treated_as_a_model_failure():
    invented = SYN_MISMATCH.model_copy(update={"applicable_clause_id": "IVP-9.9"})
    orchestrator, *_ = build(llm=FakeLlm(synthesis=invented))
    result = await orchestrator.investigate(request())
    assert result.status == "PARTIAL"
    assert result.findings.policy_agent.status == "INCOMPLETE"
    assert result.recommendation.action_type == "REQUEST_CORRECTED_INVOICE"  # rule fallback


async def test_an_unseeded_knowledge_base_is_reported_not_hidden():
    orchestrator, *_ = build(vectors=FakeVectorStore(clauses=[], incidents=[]))
    result = await orchestrator.investigate(request())
    assert result.findings.policy_agent.status == "INCOMPLETE"
    assert "No policy clauses" in result.findings.policy_agent.reason
    assert result.status == "PARTIAL"


async def test_a_missing_record_fails_the_investigation_without_calling_the_model():
    orchestrator, llm, vectors, _ = build()
    missing_po = References(invoice_id="INV-10492", po_id="PO-99999", vendor_id="VEND-3391")
    result = await orchestrator.investigate(request(missing_po))
    body = as_contract_json(result)

    assert result.status == "FAILED"
    assert "root_cause" not in body and "recommendation" not in body
    assert body["findings"]["investigation_agent"]["status"] == "INCOMPLETE"
    assert llm.embed_calls == [] and llm.prompts == [] and vectors.calls == []  # nothing wasted
    assert result.agent_trace[-1].tool_output["outcome"] == "FAILED"


async def test_a_database_failure_propagates_for_a_500():
    orchestrator, *_ = build(vectors=FakeVectorStore(error=DataAccessError("db down")))
    with pytest.raises(DataAccessError):
        await orchestrator.investigate(request())


async def test_a_bad_request_to_the_model_falls_back_too():
    orchestrator, *_ = build(llm=FakeLlm(generate_error=LlmRequestError("rejected (400)")))
    result = await orchestrator.investigate(request())
    assert result.status == "PARTIAL" and result.root_cause is not None


def test_the_search_text_mirrors_a_stored_incident_summary():
    text = describe_situation(
        "PO_INVOICE_MISMATCH",
        "PO_INVOICE_AMOUNT_MISMATCH",
        analyze_amounts(Decimal("52000.00"), Decimal("45000.00"), Decimal("2")),
    )
    assert text.startswith("Root cause: PO_INVOICE_AMOUNT_MISMATCH.")
    assert "52000.00" in text and "45000.00" in text and "15.56%" in text and "outside" in text
