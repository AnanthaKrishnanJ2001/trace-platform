import pytest

from app.agents.investigation import analyze_amounts
from app.agents.synthesis import NO_CLAUSE, RootCauseSynthesizer, Synthesis, build_prompt
from app.deadline import Deadline
from app.llm.client import LlmRequestError
from tests.fakes import CLAUSE_42, CLAUSE_43, INC_8721, SYN_MISMATCH, VENDOR_ABC, FakeLlm
from decimal import Decimal

ANALYSIS = analyze_amounts(Decimal("52000.00"), Decimal("45000.00"), Decimal("2"))


def kwargs(**over):
    base = dict(
        exception_type="PO_INVOICE_MISMATCH",
        label="PO_INVOICE_AMOUNT_MISMATCH",
        analysis=ANALYSIS,
        vendor=VENDOR_ABC,
        clauses=[CLAUSE_42, CLAUSE_43],
        precedents=[INC_8721],
    )
    base.update(over)
    return base


def test_prompt_contains_the_facts_clauses_precedents_and_allowed_actions():
    prompt = build_prompt(**kwargs())
    for expected in (
        "52000.00",
        "45000.00",
        "7000.00",
        "15.56%",
        "outside tolerance",
        "ABC Technologies",
        "IVP-4.2",
        "IVP-4.3",
        "INC-8721",
        "Vendor issued corrected invoice.",
        "REQUEST_CORRECTED_INVOICE",
        "APPROVE_WITHIN_TOLERANCE",
        "ESCALATE_TO_HUMAN",
    ):
        assert expected in prompt


def test_prompt_states_when_nothing_was_retrieved():
    prompt = build_prompt(**kwargs(clauses=[], precedents=[]))
    assert "no policy clauses were retrieved" in prompt
    assert "no similar past incidents" in prompt


async def test_valid_answer_is_returned():
    llm = FakeLlm(synthesis=SYN_MISMATCH)
    result = await RootCauseSynthesizer(llm).run(**kwargs(), deadline=Deadline(7))
    assert result == SYN_MISMATCH
    assert len(llm.prompts) == 1


async def test_none_is_an_acceptable_clause():
    answer = SYN_MISMATCH.model_copy(
        update={"applicable_clause_id": NO_CLAUSE, "policy_violated": False}
    )
    result = await RootCauseSynthesizer(FakeLlm(synthesis=answer)).run(
        **kwargs(), deadline=Deadline(7)
    )
    assert result.applicable_clause_id == NO_CLAUSE


async def test_a_clause_the_model_was_not_shown_is_rejected():
    invented = SYN_MISMATCH.model_copy(update={"applicable_clause_id": "IVP-9.9"})
    with pytest.raises(LlmRequestError, match="not in the retrieved set"):
        await RootCauseSynthesizer(FakeLlm(synthesis=invented)).run(
            **kwargs(), deadline=Deadline(7)
        )


def test_the_schema_restricts_action_type_to_the_allowed_set():
    with pytest.raises(ValueError):
        Synthesis(
            applicable_clause_id="IVP-4.2",
            policy_violated=True,
            reasoning="r",
            action_type="WIRE_MONEY",
            action_reason="x",
        )


def test_prompt_asks_for_the_breached_clause_not_the_follow_up_action_clause():
    # Live run: the model named the corrective-action clause instead of the one that
    # states the breached rule, because the instruction only said "governs".
    prompt = build_prompt(**kwargs())
    assert "state" in prompt and "not a clause that only" in prompt
    schema = Synthesis.model_json_schema()
    assert (
        "requirement the facts breach"
        in schema["properties"]["applicable_clause_id"]["description"]
    )


def test_prompt_says_a_variance_within_tolerance_is_not_a_violation():
    # Live run: after asking for the "breached" clause, the model reported IVP-4.2
    # violated=true for a 1.11% overage inside a 2% tolerance, contradicting its own
    # decision to approve it. The tolerance rule has to be stated outright.
    within = analyze_amounts(Decimal("45500.00"), Decimal("45000.00"), Decimal("2"))
    prompt = build_prompt(**kwargs(analysis=within, precedents=[]))
    assert "The variance is within tolerance." in prompt
    assert "NOT a" in prompt and "violation" in prompt
    assert "policy_violated to false" in prompt
