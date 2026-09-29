from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from decimal import Decimal, ROUND_HALF_UP

from django.db.models import Case, Exists, IntegerField, OuterRef, Prefetch, Q, Subquery, Value, When
from django.db.models.functions import Coalesce

from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.patients.models import PatientProfile, TreatmentPlan
from apps.singing.models import SingingSession

from .calculations import (
    METRIC_VERSION,
    calculate_burp_improvement,
    calculate_burp_rate,
    calculate_score_trend,
    calculate_treatment_progress,
)
from .dto import PatientMetricFilters


MONEY_QUANTUM = Decimal("0.01")


def completed_sessions():
    """返回业务上已完成的演唱；分析结果只影响依赖分析的嗳气指标。"""
    current_audio_result = AnalysisResult.objects.filter(
        task__target_type=AnalysisTask.TargetType.SINGING_SESSION,
        task__target_id=OuterRef("pk"),
        task__task_type=AnalysisTask.TaskType.SINGING_AUDIO_METRICS,
        task__generation=OuterRef("analysis_generation"),
        task__status=AnalysisTask.Status.SUCCEEDED,
        protocol_version="1.0",
    )
    return SingingSession.objects.filter(
        status=SingingSession.Status.COMPLETED,
        completed_at__isnull=False,
    ).annotate(has_current_audio_result=Exists(current_audio_result))


def _current_plan_queryset():
    return TreatmentPlan.objects.filter(
        patient_id=OuterRef("pk"),
        deleted_at__isnull=True,
    ).annotate(
        current_priority=Case(
            When(status=TreatmentPlan.Status.ACTIVE, then=Value(0)),
            default=Value(1),
            output_field=IntegerField(),
        )
    ).order_by("current_priority", "-start_date", "-created_at", "-id")


def _metric_plan_prefetch():
    return TreatmentPlan.objects.filter(deleted_at__isnull=True).annotate(
        current_priority=Case(
            When(status=TreatmentPlan.Status.ACTIVE, then=Value(0)),
            default=Value(1),
            output_field=IntegerField(),
        )
    ).order_by("current_priority", "-start_date", "-created_at", "-id")


def _with_current_plan(queryset):
    current_plan = _current_plan_queryset()
    return queryset.annotate(
        current_plan_id=Subquery(current_plan.values("id")[:1]),
        current_plan_status=Subquery(current_plan.values("status")[:1]),
    ).select_related("user", "primary_doctor").prefetch_related(
        Prefetch("treatment_plans", queryset=_metric_plan_prefetch(), to_attr="metric_plans")
    )


def filtered_patients(filters: PatientMetricFilters, *, selected_ids=()):
    queryset = _with_current_plan(PatientProfile.objects.filter(
        deleted_at__isnull=True,
        user__deleted_at__isnull=True,
    ))
    if filters.name:
        queryset = queryset.filter(name__icontains=filters.name)
    if filters.medical_record_no:
        queryset = queryset.filter(medical_record_no__icontains=filters.medical_record_no)
    if filters.primary_doctor:
        queryset = queryset.filter(primary_doctor_id=filters.primary_doctor)
    if filters.created_from:
        queryset = queryset.filter(created_at__date__gte=filters.created_from)
    if filters.created_to:
        queryset = queryset.filter(created_at__date__lte=filters.created_to)
    if filters.treatment_status == "none":
        queryset = queryset.filter(current_plan_id__isnull=True)
    elif filters.treatment_status:
        queryset = queryset.filter(current_plan_status=filters.treatment_status)
    if selected_ids:
        queryset = queryset.filter(pk__in=selected_ids)
    return queryset.order_by("medical_record_no", "id")


def patients_for_export_snapshot(patient_ids):
    """按创建时固定 ID 读取患者；后续软删不改变该 Job 的患者集合。"""
    return _with_current_plan(PatientProfile.objects.filter(pk__in=patient_ids))


def _current_plan(patient):
    plans = getattr(patient, "metric_plans", [])
    current_plan_id = getattr(patient, "current_plan_id", None)
    if current_plan_id:
        return next((plan for plan in plans if plan.id == current_plan_id), None)
    return plans[0] if plans else None


def _format_decimal(value: Decimal | None) -> str | None:
    return None if value is None else format(value, "f")


@dataclass
class _PatientAccumulator:
    completed_count: int = 0
    total_duration_seconds: int = 0
    completed_for_plan: int = 0
    score_count: int = 0
    score_sum: Decimal = Decimal("0")
    recent_scores: deque = field(default_factory=lambda: deque(maxlen=6))
    burp_rate_count: int = 0
    first_burp_rates: list = field(default_factory=list)
    recent_burp_rates: deque = field(default_factory=lambda: deque(maxlen=3))
    all_mock: bool = True


def patient_metric_rows(patients, *, heartbeat=None) -> list[dict[str, object]]:
    patients = list(patients)
    patient_ids = [patient.id for patient in patients]
    plans = {patient.id: _current_plan(patient) for patient in patients}
    accumulators = {patient.id: _PatientAccumulator() for patient in patients}
    if patient_ids:
        sessions = completed_sessions().filter(patient_id__in=patient_ids).annotate(
            occurred_at=Coalesce("submitted_at", "created_at"),
        ).values(
            "id", "patient_id", "treatment_plan_id", "score", "burp_count", "duration_seconds",
            "is_mock", "has_current_audio_result", "occurred_at",
        # 当前模型没有 started_at，submitted_at 是演唱发生时刻的持久快照；历史空值
        # 回退 created_at，同刻以 UUID 升序稳定排序，保证 SQLite/PostgreSQL 一致。
        ).order_by("patient_id", "occurred_at", "id")
        for session in sessions.iterator(chunk_size=1000):
            if heartbeat:
                heartbeat()
            accumulator = accumulators[session["patient_id"]]
            plan = plans[session["patient_id"]]
            accumulator.completed_count += 1
            accumulator.total_duration_seconds += session["duration_seconds"] or 0
            accumulator.completed_for_plan += int(bool(plan and session["treatment_plan_id"] == plan.id))
            accumulator.all_mock = accumulator.all_mock and bool(session["is_mock"])
            score = session["score"]
            if score is not None and 0 <= score <= 100:
                decimal_score = Decimal(score)
                accumulator.score_count += 1
                accumulator.score_sum += decimal_score
                accumulator.recent_scores.append(decimal_score)
            if session["has_current_audio_result"] and session["burp_count"] is not None:
                rate = calculate_burp_rate(session["burp_count"], session["duration_seconds"] or 0)
                if rate is not None:
                    accumulator.burp_rate_count += 1
                    if len(accumulator.first_burp_rates) < 3:
                        accumulator.first_burp_rates.append(rate)
                    accumulator.recent_burp_rates.append(rate)
    rows = []
    for patient in patients:
        accumulator = accumulators[patient.id]
        plan = plans[patient.id]
        average_score = (
            (accumulator.score_sum / Decimal(accumulator.score_count)).quantize(MONEY_QUANTUM, rounding=ROUND_HALF_UP)
            if accumulator.score_count else None
        )
        trend = calculate_score_trend(list(accumulator.recent_scores))
        burp_window = (
            accumulator.first_burp_rates + list(accumulator.recent_burp_rates)
            if accumulator.burp_rate_count >= 3 else []
        )
        rows.append({
            "id": str(patient.id),
            "medical_record_no": patient.medical_record_no,
            "name": patient.name,
            "treatment_status": plan.status if plan else "none",
            "primary_doctor": patient.primary_doctor.name,
            "primary_doctor_id": str(patient.primary_doctor_id),
            "treatment_progress": _format_decimal(calculate_treatment_progress(accumulator.completed_for_plan, plan.target_session_count)) if plan else None,
            "completed_count": accumulator.completed_count,
            "total_duration_seconds": accumulator.total_duration_seconds,
            "average_score": _format_decimal(average_score),
            "score_trend": {
                "difference": _format_decimal(trend["difference"]),
                "direction": trend["direction"],
                "has_enough_data": trend["has_enough_data"],
            },
            "burp_improvement": _format_decimal(calculate_burp_improvement(burp_window)),
            "is_mock": bool(accumulator.completed_count) and accumulator.all_mock,
        })
    return rows


def dashboard_metrics() -> dict[str, object]:
    active_patient_count = PatientProfile.objects.filter(
        deleted_at__isnull=True,
        user__deleted_at__isnull=True,
        treatment_plans__status=TreatmentPlan.Status.ACTIVE,
        treatment_plans__deleted_at__isnull=True,
    ).distinct().count()
    completed_count = score_count = burp_count = 0
    score_sum = burp_sum = Decimal("0")
    all_mock = True
    sessions = completed_sessions().values("score", "burp_count", "is_mock", "has_current_audio_result")
    for item in sessions.iterator(chunk_size=1000):
        completed_count += 1
        all_mock = all_mock and bool(item["is_mock"])
        if item["score"] is not None and 0 <= item["score"] <= 100:
            score_count += 1
            score_sum += Decimal(item["score"])
        if item["has_current_audio_result"] and item["burp_count"] is not None and item["burp_count"] >= 0:
            burp_count += 1
            burp_sum += Decimal(item["burp_count"])
    average_score = (score_sum / Decimal(score_count)).quantize(MONEY_QUANTUM, rounding=ROUND_HALF_UP) if score_count else None
    average_burps = (burp_sum / Decimal(burp_count)).quantize(MONEY_QUANTUM, rounding=ROUND_HALF_UP) if burp_count else None
    return {
        "metric_version": METRIC_VERSION,
        "active_patient_count": active_patient_count,
        "completed_session_count": completed_count,
        "average_score": _format_decimal(average_score),
        "average_burp_count": _format_decimal(average_burps),
        "is_mock": bool(completed_count) and all_mock,
    }


def exportable_row(row):
    trend = row["score_trend"]
    difference = trend["difference"]
    trend_text = "数据不足" if not trend["has_enough_data"] else f"{Decimal(difference):+0.2f}"
    improvement = row["burp_improvement"]
    return {
        **row,
        "treatment_status": {"active": "进行中", "pending": "待开始", "completed": "已完成", "cancelled": "已取消", "none": "无计划"}[row["treatment_status"]],
        "score_trend": trend_text,
        "burp_improvement": "暂无趋势" if improvement is None else f"{Decimal(improvement) * Decimal('100'):.2f}%",
        "is_mock": "是" if row["is_mock"] else "否",
    }
