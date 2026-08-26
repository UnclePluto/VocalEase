from django.db.models import (
    Case,
    Count,
    IntegerField,
    OuterRef,
    Prefetch,
    Q,
    Sum,
    Subquery,
    Value,
    When,
)
from django.db.models.functions import Coalesce
from django.utils import timezone

from apps.analytics.calculations import calculate_treatment_progress
from apps.singing.models import SingingSession
from .models import PatientProfile, TreatmentPlan


_ACTIVE_TREATMENT_PLAN_UNSET = object()


def visible_treatment_plans():
    return TreatmentPlan.objects.filter(deleted_at__isnull=True).order_by(
        Case(
            When(
                status__in=[TreatmentPlan.Status.PENDING, TreatmentPlan.Status.ACTIVE],
                then=Value(0),
            ),
            default=Value(1),
            output_field=IntegerField(),
        ),
        "-start_date",
        "-created_at",
    )


def patients_for_list(
    *, keyword: str = "", gender: str = "", primary_doctor: str = "", status: str = ""
):
    plans = visible_treatment_plans()
    queryset = (
        PatientProfile.objects.filter(
            deleted_at__isnull=True,
            user__deleted_at__isnull=True,
        )
        .select_related("user", "primary_doctor")
        .annotate(
            current_treatment_status=Subquery(
                plans.filter(patient_id=OuterRef("pk")).values("status")[:1]
            )
        )
        .prefetch_related(
            Prefetch(
                "treatment_plans", queryset=plans, to_attr="visible_treatment_plans"
            )
        )
    )
    if keyword:
        queryset = queryset.filter(
            Q(name__icontains=keyword)
            | Q(medical_record_no__icontains=keyword)
            | Q(phone__icontains=keyword)
        )
    if gender:
        queryset = queryset.filter(gender=gender)
    if primary_doctor:
        queryset = queryset.filter(primary_doctor_id=primary_doctor)
    if status:
        queryset = queryset.filter(current_treatment_status=status)
    return queryset


def active_treatment_plan(*, patient: PatientProfile):
    return TreatmentPlan.objects.filter(
        patient=patient,
        status=TreatmentPlan.Status.ACTIVE,
        deleted_at__isnull=True,
    ).first()


def patient_treatment_progress(
    *,
    patient: PatientProfile,
    plan=_ACTIVE_TREATMENT_PLAN_UNSET,
    today=None,
):
    today = today or timezone.localdate()
    if plan is _ACTIVE_TREATMENT_PLAN_UNSET:
        plan = active_treatment_plan(patient=patient)
    if plan is None:
        return None
    completed = SingingSession.objects.filter(
        patient=patient,
        treatment_plan=plan,
        status=SingingSession.Status.COMPLETED,
        completed_at__isnull=False,
    ).count()
    elapsed_week = ((today - plan.start_date).days // 7) + 1
    current_week = min(max(elapsed_week, 1), plan.cycle_weeks)
    progress = calculate_treatment_progress(completed, plan.target_session_count)
    return {
        "completed_session_count": completed,
        "target_session_count": plan.target_session_count,
        "progress_percent": format(progress, "f") if progress is not None else None,
        "current_week": current_week,
    }


def patient_singing_summary(*, patient: PatientProfile):
    return SingingSession.objects.filter(
        patient=patient,
        status=SingingSession.Status.COMPLETED,
        completed_at__isnull=False,
    ).aggregate(
        completed_session_count=Count("id"),
        total_duration_seconds=Coalesce(Sum("duration_seconds"), 0),
    )
