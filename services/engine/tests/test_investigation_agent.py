from decimal import Decimal

import pytest

from app.agents.investigation import TOOLS_CALLED, InvestigationAgent
from app.agents.trace import TraceRecorder
from app.api.schemas import References
from app.db import DataAccessError
from app.tools.reference_data import ReferenceDataTools
from tests.fakes import FakeCache, demo_store
from tests.test_contract import validate_against_contract

DEMO_REFS = References(invoice_id="INV-10492", po_id="PO-7821", vendor_id="VEND-3391")
TOLERANCE_REFS = References(invoice_id="INV-10493", po_id="PO-7822", vendor_id="VEND-4410")


def make_agent(store=None):
    store = store or demo_store()
    return InvestigationAgent(ReferenceDataTools(store, FakeCache())), store


async def test_demo_case_finds_the_amount_mismatch():
    agent, _ = make_agent()
    outcome = await agent.run(DEMO_REFS, 2.0, TraceRecorder())

    findings = outcome.findings
    assert findings.status == "OK"
    assert findings.invoice_amount == Decimal("52000.00")
    assert findings.po_amount == Decimal("45000.00")
    assert findings.difference == Decimal("7000.00")
    assert (
        findings.tools_called
        == TOOLS_CALLED
        == [
            "get_invoice",
            "get_purchase_order",
            "get_vendor",
        ]
    )
    assert outcome.analysis.within_tolerance is False
    assert outcome.analysis.difference_pct == Decimal("15.56")
    assert outcome.vendor.name == "ABC Technologies"


async def test_small_overage_is_within_tolerance():
    agent, _ = make_agent()
    outcome = await agent.run(TOLERANCE_REFS, 2.0, TraceRecorder())
    assert outcome.analysis.difference == Decimal("500.00")
    assert outcome.analysis.within_tolerance is True


async def test_tolerance_comes_from_the_caller():
    agent, _ = make_agent()
    strict = await agent.run(TOLERANCE_REFS, 1.0, TraceRecorder())
    assert strict.analysis.within_tolerance is False  # 1.11% > 1%


async def test_findings_serialize_to_the_contract_shape():
    agent, _ = make_agent()
    outcome = await agent.run(DEMO_REFS, 2.0, TraceRecorder())
    emitted = outcome.findings.model_dump(mode="json", exclude_none=True)
    validate_against_contract("InvestigationAgentFindings", emitted)
    assert emitted["difference"] == 7000.0  # a JSON number, not a string


async def test_every_tool_call_is_traced_with_input_and_output():
    agent, _ = make_agent()
    recorder = TraceRecorder()
    await agent.run(DEMO_REFS, 2.0, recorder)

    steps = {s.tool: s for s in recorder.steps}
    assert set(steps) == set(TOOLS_CALLED)
    assert all(s.agent == "investigation" and s.step == "TOOL_CALL" for s in recorder.steps)
    assert steps["get_invoice"].tool_input == {"invoice_id": "INV-10492"}
    assert steps["get_invoice"].tool_output["invoice_amount"] == 52000.0
    assert steps["get_purchase_order"].tool_output["approved_amount"] == 45000.0
    assert steps["get_vendor"].tool_output["name"] == "ABC Technologies"


@pytest.mark.parametrize(
    ("refs", "expected_reason"),
    [
        (
            References(invoice_id="INV-99999", po_id="PO-7821", vendor_id="VEND-3391"),
            "invoice INV-99999",
        ),
        (
            References(invoice_id="INV-10492", po_id="PO-99999", vendor_id="VEND-3391"),
            "purchase order PO-99999",
        ),
        (
            References(invoice_id="INV-10492", po_id="PO-7821", vendor_id="VEND-99999"),
            "vendor VEND-99999",
        ),
    ],
    ids=["invoice", "po", "vendor"],
)
async def test_a_missing_record_yields_an_incomplete_section(refs, expected_reason):
    agent, _ = make_agent()
    recorder = TraceRecorder()
    outcome = await agent.run(refs, 2.0, recorder)

    assert outcome.findings.status == "INCOMPLETE"
    assert expected_reason in outcome.findings.reason
    assert outcome.findings.invoice_amount is None  # no partial numbers on an INCOMPLETE section
    assert outcome.analysis is None
    not_found = [s for s in recorder.steps if s.tool_output == {"found": False}]
    assert len(not_found) == 1  # the trace shows exactly which lookup came back empty
    emitted = outcome.findings.model_dump(mode="json", exclude_none=True)
    validate_against_contract("InvestigationAgentFindings", emitted)


async def test_missing_references_are_reported_without_any_lookups():
    agent, store = make_agent()
    recorder = TraceRecorder()
    outcome = await agent.run(References(invoice_id="INV-10492"), 2.0, recorder)

    assert outcome.findings.status == "INCOMPLETE"
    assert "po_id" in outcome.findings.reason and "vendor_id" in outcome.findings.reason
    assert store.calls == [] and recorder.steps == []


async def test_a_database_failure_propagates_after_all_lookups_settle():
    agent, store = make_agent(demo_store(failing={"get_purchase_order"}))
    with pytest.raises(DataAccessError):
        await agent.run(DEMO_REFS, 2.0, TraceRecorder())
    # The other two lookups ran to completion rather than being abandoned mid-flight.
    assert {tool for tool, _ in store.calls} == set(TOOLS_CALLED)
