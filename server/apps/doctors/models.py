from django.conf import settings
from django.db import models

from common.models import UUIDSoftDeleteModel


class Gender(models.TextChoices):
    MALE = "male", "男"
    FEMALE = "female", "女"


class SequenceCounter(models.Model):
    prefix = models.CharField(max_length=8, unique=True)
    value = models.PositiveBigIntegerField(default=0)


class DoctorProfile(UUIDSoftDeleteModel):
    user = models.OneToOneField(settings.AUTH_USER_MODEL, on_delete=models.PROTECT, related_name="doctor_profile")
    employee_no = models.CharField(max_length=16, unique=True)
    name = models.CharField(max_length=64)
    gender = models.CharField(max_length=10, choices=Gender.choices)
    phone = models.CharField(max_length=32)
    department = models.CharField(max_length=64)
    title = models.CharField(max_length=64)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["employee_no"]
        constraints = [
            models.UniqueConstraint(
                fields=("phone",), name="doctor_phone_global_unique"
            )
        ]
