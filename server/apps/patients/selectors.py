from django.db.models import (
    Case,
    IntegerField,
    OuterRef,
    Prefetch,
    Q,
    Subquery,
    Value,
    When,
)

from .models import PatientProfile, TreatmentPlan


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
