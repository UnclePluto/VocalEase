from django.db import models

from common.models import UUIDSoftDeleteModel


class MediaAsset(UUIDSoftDeleteModel):
    class OwnerType(models.TextChoices):
        PATIENT = "patient", "患者"
        SONG = "song", "歌曲"
        SYSTEM = "system", "系统"
        EXPORT = "export", "导出"

    class Status(models.TextChoices):
        UPLOADING = "uploading", "上传中"
        READY = "ready", "可用"
        FAILED = "failed", "失败"
        PENDING_CLEANUP = "pending_cleanup", "待清理"

    patient_owner = models.ForeignKey("patients.PatientProfile", on_delete=models.PROTECT, related_name="media_assets", null=True, blank=True)
    owner_type = models.CharField(max_length=16, choices=OwnerType.choices)
    owner_id = models.UUIDField()
    media_type = models.CharField(max_length=32, choices=[(key, key) for key in ("song_source", "song_accompaniment", "song_vocal", "lyrics", "singing_audio", "singing_video", "waveform", "export")])
    backend = models.CharField(max_length=16, choices=[("local", "本地"), ("qiniu", "七牛")])
    object_key = models.CharField(max_length=255, unique=True)
    mime = models.CharField(max_length=127)
    size = models.PositiveBigIntegerField()
    sha256 = models.CharField(max_length=64, blank=True)
    etag = models.CharField(max_length=128, blank=True)
    status = models.CharField(max_length=24, choices=Status.choices, default=Status.UPLOADING)
    upload_expires_at = models.DateTimeField()
    metadata = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        indexes = [models.Index(fields=["patient_owner", "status"]), models.Index(fields=["media_type", "status"])]
        constraints = [
            models.CheckConstraint(condition=models.Q(backend__in=["local", "qiniu"]), name="media_asset_backend_valid"),
            models.CheckConstraint(condition=models.Q(owner_type__in=["patient", "song", "system", "export"]), name="media_asset_owner_type_valid"),
            models.CheckConstraint(condition=models.Q(status__in=["uploading", "ready", "failed", "pending_cleanup"]), name="media_asset_status_valid"),
            models.CheckConstraint(condition=(models.Q(status="ready", backend="local", sha256__regex=r"^[0-9a-f]{64}$") | models.Q(status="ready", backend="qiniu", etag__gt="") | ~models.Q(status="ready")), name="media_asset_ready_receipt_valid"),
        ]
        ordering = ["-created_at"]
