from .models import SingingSession


def sessions_for_patient(*, patient_id, created_from=None, created_to=None):
    queryset = SingingSession.objects.filter(patient_id=patient_id).select_related(
        "patient", "song", "treatment_plan"
    ).prefetch_related("media_bindings__asset")
    if created_from:
        queryset = queryset.filter(created_at__date__gte=created_from)
    if created_to:
        queryset = queryset.filter(created_at__date__lte=created_to)
    return queryset


def sessions_for_admin(*, patient_id=None, status="", created_from=None, created_to=None):
    queryset = SingingSession.objects.select_related(
        "patient", "song", "treatment_plan"
    ).prefetch_related("media_bindings__asset")
    if patient_id:
        queryset = queryset.filter(patient_id=patient_id)
    if status:
        queryset = queryset.filter(status=status)
    if created_from:
        queryset = queryset.filter(created_at__date__gte=created_from)
    if created_to:
        queryset = queryset.filter(created_at__date__lte=created_to)
    return queryset
