from decimal import Decimal

import pytest

from app.agents.investigation import analyze_amounts
from app.agents.rootcause import (
    PO_INVOICE_AMOUNT_MISMATCH,
    WITHIN_TOLERANCE,
    determine_label,
    fallback_recommendation,
    is_decisive,
    score_confidence,
)
from tests.fakes import INC_8721

D = Decimal
DEMO = analyze_amounts(D("52000"), D("45000"), D("2"))  # 15.56%: far outside 2%
SMALL = analyze_amounts(D("45500"), D("45000"), D("2"))  # 1.11%: inside 2% by 0.89 points
BORDERLINE = analyze_amounts(D("45900"), D("45000"), D("2"))  # exactly 2.00%
UNDER = analyze_amounts(D("40000"), D("45000"), D("2"))  # 11.11% below


def precedent(similarity, root_cause=PO_INVOICE_AMOUNT_MISMATCH):
    from dataclasses import replace

    return replace(INC_8721, similarity=similarity, root_cause=root_cause)


def test_labels_follow_the_arithmetic():
    assert determine_label(DEMO) == PO_INVOICE_AMOUNT_MISMATCH
    assert determine_label(SMALL) == WITHIN_TOLERANCE
    assert determine_label(BORDERLINE) == WITHIN_TOLERANCE  # exactly on the limit counts as within


@pytest.mark.parametrize(
    ("analysis", "decisive"),
    [
        (DEMO, True),
        (SMALL, True),
        (BORDERLINE, False),
        (analyze_amounts(D("45990"), D("45000"), D("2")), False),
    ],
    ids=["far-outside", "clearly-inside", "on-the-limit", "just-outside"],
)
def test_borderline_variances_are_not_decisive(analysis, decisive):
    assert is_decisive(analysis) is decisive


@pytest.mark.parametrize(
    ("label", "analysis", "policy_violated", "prec", "expected"),
    [
        # 40 base + 30 decisive + 15 policy agrees + round(15 * 0.71) = 11
        (PO_INVOICE_AMOUNT_MISMATCH, DEMO, True, precedent(0.71), 96),
        # a perfect precedent would push past 99; capped so we never claim certainty
        (PO_INVOICE_AMOUNT_MISMATCH, DEMO, True, precedent(1.0), 99),
        # no precedent at all
        (PO_INVOICE_AMOUNT_MISMATCH, DEMO, True, None, 85),
        # policy check unavailable (None): no policy points
        (PO_INVOICE_AMOUNT_MISMATCH, DEMO, None, None, 70),
        # policy disagrees with the numbers (says not violated for a clear overage)
        (PO_INVOICE_AMOUNT_MISMATCH, DEMO, False, None, 70),
        # precedent had a different root cause: no credit
        (PO_INVOICE_AMOUNT_MISMATCH, DEMO, True, precedent(0.9, root_cause="SOMETHING_ELSE"), 85),
        # within tolerance, policy correctly says not violated
        (WITHIN_TOLERANCE, SMALL, False, None, 85),
        # borderline: no decisive credit
        (WITHIN_TOLERANCE, BORDERLINE, False, None, 55),
        # nothing but the baseline
        (PO_INVOICE_AMOUNT_MISMATCH, BORDERLINE, None, None, 40),
    ],
    ids=[
        "demo-with-precedent",
        "capped-at-99",
        "no-precedent",
        "no-policy",
        "policy-disagrees",
        "precedent-other-cause",
        "within-tolerance",
        "borderline",
        "baseline-only",
    ],
)
def test_confidence_from_evidence(label, analysis, policy_violated, prec, expected):
    assert (
        score_confidence(
            label=label, analysis=analysis, policy_violated=policy_violated, precedent=prec
        )
        == expected
    )


def test_fallback_recommendations_by_situation():
    over = fallback_recommendation(PO_INVOICE_AMOUNT_MISMATCH, DEMO)
    assert over.action_type == "REQUEST_CORRECTED_INVOICE" and "15.56%" in over.reason
    within = fallback_recommendation(WITHIN_TOLERANCE, SMALL)
    assert within.action_type == "APPROVE_WITHIN_TOLERANCE"
    under = fallback_recommendation(PO_INVOICE_AMOUNT_MISMATCH, UNDER)
    assert (
        under.action_type == "ESCALATE_TO_HUMAN"
    )  # under-billing isn't a policy breach; a person decides
    assert all(r.requires_approval for r in (over, within, under))
