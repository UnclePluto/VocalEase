from django.db.models import Q

from .models import DoctorProfile


def doctors_for_list(*, keyword: str = "", department: str = "", status: str = ""):
    queryset = DoctorProfile.objects.filter(
        deleted_at__isnull=True, user__deleted_at__isnull=True
    ).select_related("user")
    if keyword:
        queryset = queryset.filter(
            Q(name__icontains=keyword)
            | Q(employee_no__icontains=keyword)
            | Q(phone__icontains=keyword)
        )
    if department:
        queryset = queryset.filter(department=department)
    if status:
        queryset = queryset.filter(user__is_active=status == "active")
    return queryset
