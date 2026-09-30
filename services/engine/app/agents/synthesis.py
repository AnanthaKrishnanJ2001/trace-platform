"""
The one language-model call in an investigation (TDD §2 step 9, the "Root Cause
Engine"). It is given the verified facts, the retrieved policy clauses and the
past incidents, and decides which clause applies, whether it is violated, what
to do, and why. Its answer is validated against what it was shown.
"""

from pydantic import BaseModel, Field

from app.agents.investigation import AmountAnalysis
from app.agents.rootcause import ACTION_DESCRIPTIONS, ActionType
from app.deadline import Deadline
from app.llm.client import LlmClient, LlmRequestError
from app.tools.records import VendorRecord
from app.tools.vector_store import IncidentMatch, PolicyClause

NO_CLAUSE = "NONE"


class Synthesis(BaseModel):
    applicable_clause_id: str = Field(
        description="The id of the listed clause whose requirement the facts breach. If no listed "
        "clause is breached, the clause that permits or governs the situation (for example a "
        f"tolerance clause); {NO_CLAUSE} if none applies."
    )
    policy_violated: bool = Field(
        description="True only if the facts breach the clause named in applicable_clause_id."
    )
    reasoning: str = Field(description="At most two sentences explaining the conclusion.")
    action_type: ActionType = Field(description="The recommended next action.")
    action_reason: str = Field(
        description="One sentence justifying the action, citing the clause id and the amounts."
    )


def build_prompt(
    *,
    exception_type: str,
    label: str,
    analysis: AmountAnalysis,
    vendor: VendorRecord | None,
    clauses: list[PolicyClause],
    precedents: list[IncidentMatch],
) -> str:
    direction = (
        "exceeds"
        if analysis.difference > 0
        else "is below"
        if analysis.difference < 0
        else "equals"
    )
    pct = "n/a" if analysis.difference_pct is None else f"{analysis.difference_pct}%"
    verdict = "within" if analysis.within_tolerance else "outside"
    clause_lines = (
        "\n".join(f"- {c.clause_id} (relevance {c.similarity:.2f}): {c.text}" for c in clauses)
        or "- (no policy clauses were retrieved)"
    )
    precedent_lines = (
        "\n".join(
            f"- {m.incident_id} (similarity {m.similarity:.2f}, outcome {m.outcome}): "
            f"{m.resolution}" + (f" Decision: {m.decision}" if m.decision else "")
            for m in precedents
        )
        or "- (no similar past incidents)"
    )
    actions = "\n".join(f"- {name}: {meaning}" for name, meaning in ACTION_DESCRIPTIONS.items())
    vendor_line = f"Vendor: {vendor.name} (risk level {vendor.risk_level})\n" if vendor else ""

    return f"""You are the root-cause step of an accounts-payable exception investigator.
Decide what to do about one flagged invoice, using only the information below.

FACTS (exact values from the system of record; treat them as correct):
Exception type: {exception_type}
{vendor_line}Invoice amount: {analysis.invoice_amount}
Purchase order approved amount: {analysis.po_amount}
The invoice {direction} the purchase order by {abs(analysis.difference)} ({pct} of the PO).
Tolerance: {analysis.tolerance_pct}%. The variance is {verdict} tolerance.
Root cause label already determined from the numbers: {label}

POLICY CLAUSES retrieved as possibly relevant:
{clause_lines}

SIMILAR PAST INCIDENTS:
{precedent_lines}

ALLOWED ACTIONS (choose exactly one action_type):
{actions}

RULES:
- applicable_clause_id must be one of the clause ids listed above, or {NO_CLAUSE}. Never invent a clause.
- policy_violated is true only if the facts above breach that clause.
- When the facts breach a rule, name the clause that states that rule, not a clause that only
  describes what to do afterwards.
- A variance that is within tolerance is permitted by the tolerance clause and is NOT a
  violation, even if the invoice is slightly above the purchase order: in that case name the
  tolerance clause and set policy_violated to false.
- Do not change or recompute any number. Do not mention information that was not provided.
- A human reviews every recommendation, so be specific and concise."""


class RootCauseSynthesizer:
    def __init__(self, llm: LlmClient) -> None:
        self._llm = llm

    async def run(
        self,
        *,
        exception_type: str,
        label: str,
        analysis: AmountAnalysis,
        vendor: VendorRecord | None,
        clauses: list[PolicyClause],
        precedents: list[IncidentMatch],
        deadline: Deadline,
    ) -> Synthesis:
        prompt = build_prompt(
            exception_type=exception_type,
            label=label,
            analysis=analysis,
            vendor=vendor,
            clauses=clauses,
            precedents=precedents,
        )
        synthesis = await self._llm.generate_structured(prompt, Synthesis, deadline)

        listed = {c.clause_id for c in clauses}
        if (
            synthesis.applicable_clause_id != NO_CLAUSE
            and synthesis.applicable_clause_id not in listed
        ):
            # The model cited a clause it wasn't shown; don't pass that on as policy.
            raise LlmRequestError("model cited a policy clause that was not in the retrieved set")
        return synthesis
