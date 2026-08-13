from django.db import models

from apps.media.contracts import MEDIA_TYPES, OWNER_MEDIA_TYPES
from common.models import UUIDSoftDeleteModel


def owner_media_condition():
    owner_type, media_types = next(iter(OWNER_MEDIA_TYPES.items()))
    condition = models.Q(owner_type=owner_type, media_type__in=sorted(media_types))
    for owner_type, media_types in list(OWNER_MEDIA_TYPES.items())[1:]:
        condition |= models.Q(owner_type=owner_type, media_type__in=sorted(media_types))
    return condition


class MediaAsset(UUIDSoftDeleteModel):
    class OwnerType(models.TextChoices):
        PATIENT = "patient", "患者"
        SONG = "song", "歌曲"
        SYSTEM = "system", "系统"
        EXPORT = "export", "导出"

    class Status(models.TextChoices):
        UPLOADING = "uploading", "上传中"
        RECEIVING = "receiving", "接收中"
        STAGED = "staged", "已暂存"
        READY = "ready", "可用"
        FAILED = "failed", "失败"
        PENDING_CLEANUP = "pending_cleanup", "待清理"

    patient_owner = models.ForeignKey("patients.PatientProfile", on_delete=models.PROTECT, related_name="media_assets", null=True, blank=True)
    owner_type = models.CharField(max_length=16, choices=OwnerType.choices)
    owner_id = models.UUIDField()
    media_type = models.CharField(max_length=32, choices=[(key, key) for key in MEDIA_TYPES])
    backend = models.CharField(max_length=16, choices=[("local", "本地"), ("qiniu", "七牛")])
    object_key = models.CharField(max_length=255, unique=True)
    mime = models.CharField(max_length=127)
    size = models.PositiveBigIntegerField()
    sha256 = models.CharField(max_length=64, blank=True)
    etag = models.CharField(max_length=128, blank=True)
    status = models.CharField(max_length=24, choices=Status.choices, default=Status.UPLOADING)
    upload_expires_at = models.DateTimeField()
    upload_nonce = models.UUIDField(null=True, blank=True)
    upload_lease_expires_at = models.DateTimeField(null=True, blank=True)
    manifest_generation = models.CharField(max_length=32, blank=True)
    metadata = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        indexes = [models.Index(fields=["patient_owner", "status"]), models.Index(fields=["media_type", "status"])]
        constraints = [
            models.CheckConstraint(condition=models.Q(backend__in=["local", "qiniu"]), name="media_asset_backend_valid"),
            models.CheckConstraint(condition=models.Q(media_type__in=["song_source", "song_accompaniment", "song_vocal", "lyrics", "singing_audio", "singing_video", "waveform", "export"]), name="media_asset_media_type_valid"),
            models.CheckConstraint(condition=owner_media_condition(), name="media_asset_owner_media_valid"),
            models.CheckConstraint(condition=models.Q(owner_type__in=["patient", "song", "system", "export"]), name="media_asset_owner_type_valid"),
            models.CheckConstraint(condition=(models.Q(("owner_type", "patient"), ("patient_owner__isnull", False), ("owner_id", models.F("patient_owner_id"))) | ~models.Q(("owner_type", "patient"))), name="media_asset_patient_owner_consistent"),
            models.CheckConstraint(condition=(models.Q(owner_type__in=["song", "system", "export"], patient_owner__isnull=True) | models.Q(owner_type="patient")), name="media_asset_nonpatient_owner_null"),
            models.CheckConstraint(condition=models.Q(status__in=["uploading", "receiving", "staged", "ready", "failed", "pending_cleanup"]), name="media_asset_status_valid"),
            models.CheckConstraint(condition=(models.Q(status="receiving", backend="local", upload_nonce__isnull=False, upload_lease_expires_at__isnull=False) | (~models.Q(status="receiving") & models.Q(upload_nonce__isnull=True, upload_lease_expires_at__isnull=True))), name="media_asset_upload_lease_valid"),
            models.CheckConstraint(condition=(models.Q(status__in=["staged", "ready"], backend="local", sha256__regex=r"^[0-9a-f]{64}$", manifest_generation__regex=r"^[0-9a-f]{32}$") | models.Q(status="ready", backend="qiniu", etag__gt="", manifest_generation="") | ~models.Q(status__in=["staged", "ready"])), name="media_asset_ready_receipt_valid"),
            models.CheckConstraint(condition=(models.Q(status="staged", backend="local") | ~models.Q(status="staged")), name="media_asset_staged_local_only"),
        ]
        ordering = ["-created_at"]
