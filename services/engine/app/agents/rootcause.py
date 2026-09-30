"""
The deterministic side of root-cause analysis: which label the numbers support,
how confident we are, and what to recommend if the model can't help. The model
adds judgement (which policy clause applies, what to do, and why) on top of
these facts; it never gets to overrule the arithmetic or invent a confidence.
"""

from decimal import Decimal
from typing import Literal

from app.agents.investigation import AmountAnalysis
from app.api.schemas import Recommendation
from app.tools.vector_store import IncidentMatch

PO_INVOICE_AMOUNT_MISMATCH = "PO_INVOICE_AMOUNT_MISMATCH"
WITHIN_TOLERANCE = "WITHIN_TOLERANCE"

ActionType = Literal["REQUEST_CORRECTED_INVOICE", "APPROVE_WITHIN_TOLERANCE", "ESCALATE_TO_HUMAN"]
# What each action means; shown to the model so it picks from a closed set.
ACTION_DESCRIPTIONS: dict[str, str] = {
    "REQUEST_CORRECTED_INVOICE": "ask the vendor for a corrected invoice or an approved change order",
    "APPROVE_WITHIN_TOLERANCE": "approve the invoice, because the variance is inside the tolerance",
    "ESCALATE_TO_HUMAN": "the evidence is unclear or unusual, so send it to a human reviewer",
}

# A variance this many percentage points away from the tolerance is clear-cut;
# closer than that it's borderline and earns no "decisive" credit.
_DECISIVE_MARGIN_PCT = Decimal("0.5")
_MAX_CONFIDENCE = 99  # never claim certainty


def determine_label(analysis: AmountAnalysis) -> str:
    return WITHIN_TOLERANCE if analysis.within_tolerance else PO_INVOICE_AMOUNT_MISMATCH


def is_decisive(analysis: AmountAnalysis) -> bool:
    if analysis.difference_pct is None:  # zero-value PO: no percentage, but never borderline
        return True
    return abs(analysis.difference_pct - analysis.tolerance_pct) >= _DECISIVE_MARGIN_PCT


def score_confidence(
    *,
    label: str,
    analysis: AmountAnalysis,
    policy_violated: bool | None,
    precedent: IncidentMatch | None,
) -> int:
    """
    Confidence in 0-99 from evidence, so it means the same thing every run:
      40  baseline: the amounts are exact database values
     +30  the variance is clearly inside or outside tolerance (not borderline)
     +15  the policy check agrees with the amounts (skipped if it was unavailable)
     +up to 15  a past incident with the same root cause, scaled by similarity
    """
    score = 40
    if is_decisive(analysis):
        score += 30
    expects_violation = label == PO_INVOICE_AMOUNT_MISMATCH
    if policy_violated is not None and policy_violated == expects_violation:
        score += 15
    if precedent is not None and precedent.root_cause == label:
        score += round(15 * precedent.similarity)
    return min(score, _MAX_CONFIDENCE)


def fallback_recommendation(label: str, analysis: AmountAnalysis) -> Recommendation:
    """Used when the model is unavailable: a plain rule, clearly worded, still needs human approval."""
    pct = "n/a" if analysis.difference_pct is None else f"{analysis.difference_pct}%"
    if label == WITHIN_TOLERANCE:
        return Recommendation(
            action_type="APPROVE_WITHIN_TOLERANCE",
            reason=f"The variance ({pct}) is within the {analysis.tolerance_pct}% tolerance.",
            requires_approval=True,
        )
    if analysis.difference > 0:
        return Recommendation(
            action_type="REQUEST_CORRECTED_INVOICE",
            reason=(
                f"The invoice exceeds the purchase order by {pct}, outside the "
                f"{analysis.tolerance_pct}% tolerance."
            ),
            requires_approval=True,
        )
    return Recommendation(
        action_type="ESCALATE_TO_HUMAN",
        reason=(
            f"The invoice is {pct} below the purchase order, outside the "
            f"{analysis.tolerance_pct}% tolerance; this needs a human to review."
        ),
        requires_approval=True,
    )
