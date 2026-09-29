from django.conf import settings
from django.db import models
from django.db.models import Q

from apps.doctors.models import DoctorProfile, Gender
from common.models import UUIDSoftDeleteModel


class PatientProfile(UUIDSoftDeleteModel):
    user = models.OneToOneField(settings.AUTH_USER_MODEL, on_delete=models.PROTECT, related_name="patient_profile")
    medical_record_no = models.CharField(max_length=16, unique=True)
    name = models.CharField(max_length=64)
    gender = models.CharField(max_length=10, choices=Gender.choices)
    enrollment_age = models.PositiveSmallIntegerField()
    phone = models.CharField(max_length=32)
    primary_doctor = models.ForeignKey(DoctorProfile, on_delete=models.PROTECT, related_name="patients")
    notes = models.TextField(blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["medical_record_no"]


class TreatmentPlan(UUIDSoftDeleteModel):
    class Status(models.TextChoices):
        PENDING = "pending", "待开始"
        ACTIVE = "active", "进行中"
        COMPLETED = "completed", "已完成"
        CANCELLED = "cancelled", "已取消"

    patient = models.ForeignKey(PatientProfile, on_delete=models.PROTECT, related_name="treatment_plans")
    start_date = models.DateField()
    cycle_weeks = models.PositiveSmallIntegerField()
    target_session_count = models.PositiveSmallIntegerField()
    status = models.CharField(max_length=16, choices=Status.choices, default=Status.PENDING)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=["patient"],
                condition=Q(status="active", deleted_at__isnull=True),
                name="one_active_treatment_plan_per_patient",
            )
        ]
        ordering = ["-start_date"]
