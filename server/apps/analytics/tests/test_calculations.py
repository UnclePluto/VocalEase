from decimal import Decimal

from apps.analytics.calculations import (
    calculate_burp_improvement,
    calculate_score_trend,
    calculate_treatment_progress,
)


def test_burp_improvement_uses_decimal_first_and_latest_three():
    rates = [Decimal("4"), Decimal("3"), Decimal("2"), Decimal("2"), Decimal("1"), Decimal("1")]
    assert calculate_burp_improvement(rates) == Decimal("0.5556")


def test_burp_improvement_requires_three_samples_and_nonzero_baseline():
    assert calculate_burp_improvement([Decimal("2"), Decimal("1")]) is None
    assert calculate_burp_improvement([Decimal("0"), Decimal("0"), Decimal("0")]) is None


def test_score_trend_compares_latest_three_to_previous_three():
    assert calculate_score_trend([Decimal("60"), Decimal("60"), Decimal("60"), Decimal("80"), Decimal("80"), Decimal("80")]) == {
        "difference": Decimal("20.00"),
        "direction": "up",
        "has_enough_data": True,
    }
    assert calculate_score_trend([Decimal("80")] * 5) == {
        "difference": None,
        "direction": "insufficient",
        "has_enough_data": False,
    }


def test_treatment_progress_caps_at_one_hundred_percent():
    assert calculate_treatment_progress(7, 6) == Decimal("100.00")
    assert calculate_treatment_progress(1, 6) == Decimal("16.67")
    assert calculate_treatment_progress(1, 0) is None
