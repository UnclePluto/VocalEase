from django.db import models

from common.models import UUIDSoftDeleteModel


class MediaAsset(UUIDSoftDeleteModel):
    class OwnerType(models.TextChoices):
        PATIENT = "patient", "患者"

    class Status(models.TextChoices):
        UPLOADING = "uploading", "上传中"
        READY = "ready", "可用"
        FAILED = "failed", "失败"
        PENDING_CLEANUP = "pending_cleanup", "待清理"

    owner = models.ForeignKey("patients.PatientProfile", on_delete=models.PROTECT, related_name="media_assets")
    owner_type = models.CharField(max_length=16, choices=OwnerType.choices, default=OwnerType.PATIENT)
    media_type = models.CharField(max_length=32)
    backend = models.CharField(max_length=16)
    object_key = models.CharField(max_length=255, unique=True)
    mime = models.CharField(max_length=127)
    size = models.PositiveBigIntegerField()
    sha256 = models.CharField(max_length=64, blank=True)
    status = models.CharField(max_length=24, choices=Status.choices, default=Status.UPLOADING)
    upload_expires_at = models.DateTimeField()
    metadata = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        indexes = [models.Index(fields=["owner", "status"]), models.Index(fields=["media_type", "status"])]
        ordering = ["-created_at"]
