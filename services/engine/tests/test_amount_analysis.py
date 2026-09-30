from decimal import Decimal

import pytest

from app.agents.investigation import analyze_amounts

D = Decimal


@pytest.mark.parametrize(
    ("invoice", "po", "tolerance", "difference", "pct", "within"),
    [
        pytest.param("52000.00", "45000.00", "2", "7000.00", "15.56", False, id="demo-case-over"),
        pytest.param("45500.00", "45000.00", "2", "500.00", "1.11", True, id="small-overage"),
        pytest.param("45900.00", "45000.00", "2", "900.00", "2.00", True, id="exactly-on-limit"),
        pytest.param(
            "45900.01", "45000.00", "2", "900.01", "2.00", False, id="a-cent-past-the-limit"
        ),
        pytest.param("44100.00", "45000.00", "2", "-900.00", "2.00", True, id="under-on-limit"),
        pytest.param("44000.00", "45000.00", "2", "-1000.00", "2.22", False, id="under-past-limit"),
        pytest.param("45000.00", "45000.00", "2", "0.00", "0.00", True, id="exact-match"),
        pytest.param("45000.00", "45000.00", "0", "0.00", "0.00", True, id="zero-tolerance-match"),
        pytest.param("45000.01", "45000.00", "0", "0.01", "0.00", False, id="zero-tolerance-cent"),
        pytest.param("100.00", "0.00", "2", "100.00", None, False, id="zero-po-nonzero-invoice"),
        pytest.param("0.00", "0.00", "2", "0.00", None, True, id="zero-po-zero-invoice"),
    ],
)
def test_amount_analysis(invoice, po, tolerance, difference, pct, within):
    result = analyze_amounts(D(invoice), D(po), D(tolerance))
    assert result.difference == D(difference)
    assert result.difference_pct == (D(pct) if pct is not None else None)
    assert result.within_tolerance is within


def test_within_tolerance_uses_exact_value_not_the_rounded_percentage():
    # 2.00002% rounds to 2.00 for display but is genuinely over a 2% limit.
    result = analyze_amounts(D("45900.01"), D("45000.00"), D("2"))
    assert result.difference_pct == D("2.00")
    assert result.within_tolerance is False


def test_money_arithmetic_is_exact_where_floats_drift():
    # 0.1 + 0.2 != 0.3 in binary floating point; amounts must not have that problem.
    result = analyze_amounts(D("0.30"), D("0.10"), D("2"))
    assert result.difference == D("0.20")
