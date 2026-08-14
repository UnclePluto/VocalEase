from __future__ import annotations

from decimal import Decimal, ROUND_HALF_UP
from typing import Sequence


METRIC_VERSION = "1.0"
PERCENT_QUANTUM = Decimal("0.01")
RATE_QUANTUM = Decimal("0.0001")


def _average(values: Sequence[Decimal]) -> Decimal:
    return sum(values, Decimal("0")) / Decimal(len(values))


def calculate_treatment_progress(completed_count: int, target_session_count: int) -> Decimal | None:
    if target_session_count <= 0:
        return None
    value = Decimal(completed_count) * Decimal("100") / Decimal(target_session_count)
    return min(value, Decimal("100")).quantize(PERCENT_QUANTUM, rounding=ROUND_HALF_UP)


def calculate_score_trend(scores: Sequence[Decimal]) -> dict[str, Decimal | str | bool | None]:
    if len(scores) < 6:
        return {"difference": None, "direction": "insufficient", "has_enough_data": False}
    difference = (_average(scores[-3:]) - _average(scores[-6:-3])).quantize(
        PERCENT_QUANTUM, rounding=ROUND_HALF_UP
    )
    direction = "up" if difference > 0 else "down" if difference < 0 else "flat"
    return {"difference": difference, "direction": direction, "has_enough_data": True}


def calculate_burp_improvement(rates: Sequence[Decimal]) -> Decimal | None:
    if len(rates) < 3:
        return None
    baseline = _average(rates[:3])
    if baseline == 0:
        return None
    current = _average(rates[-3:])
    return ((baseline - current) / baseline).quantize(RATE_QUANTUM, rounding=ROUND_HALF_UP)


def calculate_burp_rate(burp_count: int, duration_seconds: int) -> Decimal | None:
    if duration_seconds <= 0:
        return None
    return (Decimal(burp_count) * Decimal("60") / Decimal(duration_seconds)).quantize(
        RATE_QUANTUM, rounding=ROUND_HALF_UP
    )
