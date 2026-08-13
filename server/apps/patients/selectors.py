from django.db.models import Q

from .models import PatientProfile


def patients_for_list(*, keyword: str = "", gender: str = "", primary_doctor: str = ""):
    queryset = PatientProfile.objects.filter(
        deleted_at__isnull=True,
        user__deleted_at__isnull=True,
    ).select_related("user", "primary_doctor")
    if keyword:
        queryset = queryset.filter(Q(name__icontains=keyword) | Q(medical_record_no__icontains=keyword) | Q(phone__icontains=keyword))
    if gender:
        queryset = queryset.filter(gender=gender)
    if primary_doctor:
        queryset = queryset.filter(primary_doctor_id=primary_doctor)
    return queryset
